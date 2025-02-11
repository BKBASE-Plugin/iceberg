/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.hive;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.LockComponent;
import org.apache.hadoop.hive.metastore.api.LockLevel;
import org.apache.hadoop.hive.metastore.api.LockRequest;
import org.apache.hadoop.hive.metastore.api.LockResponse;
import org.apache.hadoop.hive.metastore.api.LockState;
import org.apache.hadoop.hive.metastore.api.LockType;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.iceberg.BaseMetastoreTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.common.DynMethods;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NoSuchIcebergTableException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO we should be able to extract some more commonalities to BaseMetastoreTableOperations to
 * avoid code duplication between this class and Metacat Tables.
 */
public class HiveTableOperations extends BaseMetastoreTableOperations {
  private static final Logger LOG = LoggerFactory.getLogger(HiveTableOperations.class);

  private static final String HIVE_ACQUIRE_LOCK_STATE_TIMEOUT_MS = "iceberg.hive.lock-timeout-ms";

  private static final String HIVE_TABLE_LEVEL_LOCK_EVICT_MS = "iceberg.hive.table-level-lock-evict-ms";
  private static final long HIVE_TABLE_LEVEL_LOCK_EVICT_MS_DEFAULT = TimeUnit.MINUTES.toMillis(10);

  private static final long HIVE_ACQUIRE_LOCK_STATE_TIMEOUT_MS_DEFAULT = 3 * 60 * 1000; // 3 minutes
  private static final String HIVE_CHECK_LOCK_TIMEOUT_MS = "iceberg.hive.check-lock-timeout-ms";
  private static final long HIVE_CHECK_LOCK_TIMEOUT_MS_DEFAULT = 5_000;
  private static final DynMethods.UnboundMethod ALTER_TABLE = DynMethods.builder("alter_table")
      .impl(HiveMetaStoreClient.class, "alter_table_with_environmentContext",
          String.class, String.class, Table.class, EnvironmentContext.class)
      .impl(HiveMetaStoreClient.class, "alter_table",
          String.class, String.class, Table.class, EnvironmentContext.class)
      .build();

  // commit lock 缓存，自动过期
  private static Cache<String, ReentrantLock> commitLockCache;

  private static synchronized void initTableLevelLockCache(long evictionTimeout) {
    if (commitLockCache == null) {
      commitLockCache = Caffeine.newBuilder()
              .expireAfterAccess(evictionTimeout, TimeUnit.MILLISECONDS)
              .build();
    }
  }
  private final HiveClientPool metaClients;
  private final String fullName;
  private final String database;
  private final String tableName;
  private final Configuration conf;
  private final long lockAcquireTimeout;
  private final long checkLockTimeout;

  private FileIO fileIO;

  protected HiveTableOperations(Configuration conf, HiveClientPool metaClients,
                                String catalogName, String database, String table) {
    this.conf = conf;
    this.metaClients = metaClients;
    this.fullName = catalogName + "." + database + "." + table;
    this.database = database;
    this.tableName = table;
    this.lockAcquireTimeout =
        conf.getLong(HIVE_ACQUIRE_LOCK_STATE_TIMEOUT_MS, HIVE_ACQUIRE_LOCK_STATE_TIMEOUT_MS_DEFAULT);
    this.checkLockTimeout = conf.getLong(HIVE_CHECK_LOCK_TIMEOUT_MS, HIVE_CHECK_LOCK_TIMEOUT_MS_DEFAULT);
    long tableLevelLockCacheEvictionTimeout =
            conf.getLong(HIVE_TABLE_LEVEL_LOCK_EVICT_MS, HIVE_TABLE_LEVEL_LOCK_EVICT_MS_DEFAULT);
    initTableLevelLockCache(tableLevelLockCacheEvictionTimeout);
  }

  @Override
  public FileIO io() {
    if (fileIO == null) {
      fileIO = new HadoopFileIO(conf);
    }

    return fileIO;
  }

  @Override
  protected void doRefresh() {
    String metadataLocation = null;
    try {
      Table table = metaClients.run(client -> client.getTable(database, tableName));
      validateTableIsIceberg(table, fullName);

      metadataLocation = table.getParameters().get(METADATA_LOCATION_PROP);

    } catch (NoSuchObjectException e) {
      if (currentMetadataLocation() != null) {
        throw new NoSuchTableException(String.format("No such table: %s.%s", database, tableName));
      }

    } catch (TException e) {
      String errMsg = String.format("Failed to get table info from metastore %s.%s", database, tableName);
      throw new RuntimeException(errMsg, e);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted during refresh", e);
    }

    refreshFromMetadataLocation(metadataLocation);
  }

  @Override
  protected void doCommit(TableMetadata base, TableMetadata metadata) {
    String newMetadataLocation = writeNewMetadata(metadata, currentVersion() + 1);

    CommitStatus commitStatus = CommitStatus.FAILURE;
    Optional<Long> lockId = Optional.empty();

    // 获取commit lock
    ReentrantLock tableLevelMutex = commitLockCache.get(fullName, t -> new ReentrantLock(true));
    tableLevelMutex.lock();
    try {
      lockId = Optional.of(acquireLock());
      // TODO add lock heart beating for cases where default lock timeout is too low.
      Table tbl;
      if (base != null) {
        tbl = metaClients.run(client -> client.getTable(database, tableName));
      } else {
        final long currentTimeMillis = System.currentTimeMillis();
        tbl = new Table(tableName,
            database,
            System.getProperty("user.name"),
            (int) currentTimeMillis / 1000,
            (int) currentTimeMillis / 1000,
            Integer.MAX_VALUE,
            storageDescriptor(metadata),
            Collections.emptyList(),
            new HashMap<>(),
            null,
            null,
            TableType.EXTERNAL_TABLE.toString());
        tbl.getParameters().put("EXTERNAL", "TRUE"); // using the external table type also requires this
      }
      tbl.setSd(storageDescriptor(metadata)); // set to pickup any schema changes
      String metadataLocation = tbl.getParameters().get(METADATA_LOCATION_PROP);
      String baseMetadataLocation = base != null ? base.metadataFileLocation() : null;
      if (!Objects.equals(baseMetadataLocation, metadataLocation)) {
        throw new CommitFailedException(
            "Base metadata location '%s' is not same as the current table metadata location '%s' for %s.%s",
            baseMetadataLocation, metadataLocation, database, tableName);
      }

      setParameters(newMetadataLocation, tbl);
      try {
        if (base != null) {
          metaClients.run(client -> {
            EnvironmentContext envContext = new EnvironmentContext(
                    ImmutableMap.of(StatsSetupConst.DO_NOT_UPDATE_STATS, StatsSetupConst.TRUE)
            );
            ALTER_TABLE.invoke(client, database, tableName, tbl, envContext);
            return null;
          });
        } else {
          metaClients.run(client -> {
            client.createTable(tbl);
            return null;
          });
        }
        commitStatus = CommitStatus.SUCCESS;
      } catch (Throwable persistFailure) {
        LOG.error("Cannot tell if commit to {}.{} succeeded, attempting to reconnect and check.",
                database, tableName, persistFailure);
        commitStatus = checkCommitStatus(newMetadataLocation, metadata);
        switch (commitStatus) {
          case SUCCESS:
            break;
          case FAILURE:
            throw persistFailure;
          case UNKNOWN:
            throw new CommitStateUnknownException(persistFailure);
        }
      }

    } catch (org.apache.hadoop.hive.metastore.api.AlreadyExistsException e) {
      throw new AlreadyExistsException("Table already exists: %s.%s", database, tableName);

    } catch (TException | UnknownHostException e) {
      if (e.getMessage() != null && e.getMessage().contains("Table/View 'HIVE_LOCKS' does not exist")) {
        throw new RuntimeException("Failed to acquire locks from metastore because 'HIVE_LOCKS' doesn't " +
            "exist, this probably happened when using embedded metastore or doesn't create a " +
            "transactional meta table. To fix this, use an alternative metastore", e);
      }

      throw new RuntimeException(String.format("Metastore operation failed for %s.%s", database, tableName), e);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted during commit", e);

    } finally {
      cleanupMetadataAndUnlock(commitStatus, newMetadataLocation, lockId);
      // 释放本地锁
      tableLevelMutex.unlock();
    }
  }

  private void setParameters(String newMetadataLocation, Table tbl) {
    Map<String, String> parameters = tbl.getParameters();

    if (parameters == null) {
      parameters = new HashMap<>();
    }

    parameters.put(TABLE_TYPE_PROP, ICEBERG_TABLE_TYPE_VALUE.toUpperCase(Locale.ENGLISH));
    parameters.put(METADATA_LOCATION_PROP, newMetadataLocation);

    if (currentMetadataLocation() != null && !currentMetadataLocation().isEmpty()) {
      parameters.put(PREVIOUS_METADATA_LOCATION_PROP, currentMetadataLocation());
    }

    tbl.setParameters(parameters);
  }

  private StorageDescriptor storageDescriptor(TableMetadata metadata) {

    final StorageDescriptor storageDescriptor = new StorageDescriptor();
    storageDescriptor.setCols(columns(metadata.schema()));
    storageDescriptor.setLocation(metadata.location());
    storageDescriptor.setOutputFormat("org.apache.hadoop.mapred.FileOutputFormat");
    storageDescriptor.setInputFormat("org.apache.hadoop.mapred.FileInputFormat");
    SerDeInfo serDeInfo = new SerDeInfo();
    serDeInfo.setSerializationLib("org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe");
    storageDescriptor.setSerdeInfo(serDeInfo);
    return storageDescriptor;
  }

  private List<FieldSchema> columns(Schema schema) {
    return schema.columns().stream()
        .map(col -> new FieldSchema(col.name(), HiveTypeConverter.convert(col.type()), ""))
        .collect(Collectors.toList());
  }

  private long acquireLock() throws UnknownHostException, TException, InterruptedException {
    final LockComponent lockComponent = new LockComponent(LockType.EXCLUSIVE, LockLevel.TABLE, database);
    lockComponent.setTablename(tableName);
    final LockRequest lockRequest = new LockRequest(Lists.newArrayList(lockComponent),
        System.getProperty("user.name"),
        InetAddress.getLocalHost().getHostName());
    LockResponse lockResponse = metaClients.run(client -> client.lock(lockRequest));
    LockState state = lockResponse.getState();
    long lockId = lockResponse.getLockid();

    final long start = System.currentTimeMillis();
    long duration = 0;
    long callDuration = 0;
    boolean timeout = false;
    try {
      while (!timeout && state.equals(LockState.WAITING)) {
        long callStart = System.currentTimeMillis();
        lockResponse = metaClients.run(client -> client.checkLock(lockId));
        // if check lock call takes too long, there must be something wrong with hive metastore server
        callDuration = System.currentTimeMillis() - callStart;
        state = lockResponse.getState();

        if (state.equals(LockState.ACQUIRED)) {
          break;
        }

        // check timeout
        duration = System.currentTimeMillis() - start;
        if (duration > lockAcquireTimeout || callDuration > checkLockTimeout) {
          timeout = true;
        } else {
          Thread.sleep(50);
        }
      }
    } catch (Exception e) {
      LOG.warn("Fail to acquire the lock on {}.{}", database, tableName, e);
    } finally {
      // 获取不到锁时，需要释放锁，否则会一直堆积
      if (!state.equals(LockState.ACQUIRED)) {
        unlock(Optional.of(lockId));
      }
    }

    // timeout and do not have lock acquired
    if (timeout) {
      throw new CommitFailedException(
              String.format("Timed out after %s ms waiting for lock on %s.%s. Last check lock call takes %s ms",
                      duration, database, tableName, callDuration));
    }

    if (!state.equals(LockState.ACQUIRED)) {
      throw new CommitFailedException(String.format("Could not acquire the lock on %s.%s, " +
          "lock request ended in state %s", database, tableName, state));
    }

    return lockId;
  }

  void cleanupMetadataAndUnlock(CommitStatus commitStatus, String metadataLocation, Optional<Long> lockId) {
    try {
      if (commitStatus == CommitStatus.FAILURE) {
        // if anything went wrong, clean up the uncommitted metadata file
        io().deleteFile(metadataLocation);
      }
    } catch (RuntimeException e) {
      LOG.error("Fail to cleanup metadata file at {}", metadataLocation, e);
      throw e;
    } finally {
      unlock(lockId);
    }
  }

  private void unlock(Optional<Long> lockId) {
    if (lockId.isPresent()) {
      try {
        doUnlock(lockId.get());
      } catch (Exception e) {
        LOG.warn("Failed to unlock {}.{}", database, tableName, e);
      }
    }
  }

  // visible for testing
  protected void doUnlock(long lockId) throws TException, InterruptedException {
    metaClients.run(client -> {
      client.unlock(lockId);
      return null;
    });
  }

  static void validateTableIsIceberg(Table table, String fullName) {
    String tableType = table.getParameters().get(TABLE_TYPE_PROP);
    NoSuchIcebergTableException.check(tableType != null && tableType.equalsIgnoreCase(ICEBERG_TABLE_TYPE_VALUE),
        "Not an iceberg table: %s (type=%s)", fullName, tableType);
    NoSuchIcebergTableException.check(table.getParameters().get(METADATA_LOCATION_PROP) != null,
        "Not an iceberg table: %s missing %s", fullName, METADATA_LOCATION_PROP);
  }

  protected String tableName() {
    return fullName;
  }
}
