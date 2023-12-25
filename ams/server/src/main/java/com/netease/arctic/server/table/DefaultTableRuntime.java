/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.server.table;

import com.netease.arctic.AmoroTable;
import com.netease.arctic.ams.api.Action;
import com.netease.arctic.ams.api.ServerTableIdentifier;
import com.netease.arctic.ams.api.TableRuntime;
import com.netease.arctic.ams.api.config.TableConfiguration;
import com.netease.arctic.ams.api.process.AmoroProcess;
import com.netease.arctic.ams.api.process.OptimizingState;
import com.netease.arctic.ams.api.process.PendingInput;
import com.netease.arctic.ams.api.process.ProcessFactory;
import com.netease.arctic.ams.api.process.ProcessState;
import com.netease.arctic.ams.api.process.ProcessStatus;
import com.netease.arctic.ams.api.process.TableState;
import com.netease.arctic.server.persistence.StatedPersistentBase;
import com.netease.arctic.server.persistence.TableRuntimePersistency;
import com.netease.arctic.server.persistence.mapper.TableMetaMapper;
import com.netease.arctic.server.process.optimizing.DefaultOptimizingState;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class DefaultTableRuntime extends StatedPersistentBase implements TableRuntime {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultTableRuntime.class);

  private final TableManager tableManager;
  private final ServerTableIdentifier tableIdentifier;
  private final DefaultOptimizingState optimizingState;
  private final SingletonActionRunner<OptimizingState> optimizingRunner =
      new SingletonActionRunner<>(Action.OPTIMIZING);
  private final Map<Action, SingletonActionRunner<TableState>> arbitraryRunnerMap =
      Collections.synchronizedMap(new HashMap<>());
  private final ReentrantLock tableLock = new ReentrantLock();
  private final TableBlockerRuntime blockerRuntime;
  private volatile TableConfiguration tableConfiguration;

  protected DefaultTableRuntime(
      ServerTableIdentifier tableIdentifier,
      TableManager tableManager,
      Map<String, String> properties) {
    Preconditions.checkNotNull(tableIdentifier, tableManager);
    this.tableManager = tableManager;
    this.tableIdentifier = tableIdentifier;
    this.tableConfiguration = TableConfigurations.parseConfig(properties);
    this.optimizingState = new DefaultOptimizingState(tableIdentifier);
    this.blockerRuntime = new TableBlockerRuntime(tableIdentifier);
    persistTableRuntime();
    initActionRunners();
  }

  protected DefaultTableRuntime(
      TableRuntimePersistency tableRuntimePersistency, TableManager tableManager) {
    Preconditions.checkNotNull(tableRuntimePersistency, tableManager);
    this.tableManager = tableManager;
    this.tableIdentifier =
        ServerTableIdentifier.of(
            tableRuntimePersistency.getTableId(),
            tableRuntimePersistency.getCatalogName(),
            tableRuntimePersistency.getDbName(),
            tableRuntimePersistency.getTableName(),
            tableRuntimePersistency.getFormat());
    this.optimizingState = new DefaultOptimizingState(tableIdentifier, tableRuntimePersistency);
    this.tableConfiguration = tableRuntimePersistency.getTableConfig();
    this.blockerRuntime = new TableBlockerRuntime(tableIdentifier);
    initActionRunners();
  }

  private void initActionRunners() {}

  // TODO wangtaohz: new added table not call this method
  @SuppressWarnings("unchecked")
  public void register(ProcessFactory<? extends OptimizingState> defaultOptimizingFactory) {
    optimizingRunner.install((ProcessFactory<OptimizingState>) defaultOptimizingFactory);
  }

  // TODO wangtaohz: new added table not call this method
  public void register(Set<Action> actions, ProcessFactory<TableState> processFactory) {
    actions.forEach(action -> arbitraryRunnerMap.get(action).install(processFactory));
  }

  public AmoroProcess<OptimizingState> runOptimizing() {
    return optimizingRunner.run();
  }

  public AmoroProcess<OptimizingState> runOptimizing(
      ProcessFactory<OptimizingState> processFactory) {
    return optimizingRunner.run(processFactory);
  }

  public AmoroProcess<? extends TableState> runAction(Action action) {
    Preconditions.checkState(action != null && action != Action.OPTIMIZING);
    return arbitraryRunnerMap.get(action).run();
  }

  public AmoroProcess<? extends TableState> runAction(
      Action action, ProcessFactory<TableState> processFactory) {
    Preconditions.checkState(action != null && action != Action.OPTIMIZING);
    return arbitraryRunnerMap.get(action).run(processFactory);
  }

  public void closeProcess(long processId) {
    optimizingRunner.close(processId);
    arbitraryRunnerMap.values().forEach(runner -> runner.close(processId));
  }

  public Map<Action, Long> getLastCompletedTimes(Set<Action> actions) {
    return actions.stream()
        .collect(
            Collectors.toMap(
                action -> action, action -> arbitraryRunnerMap.get(action).getLastCompletedTime()));
  }

  public void recover() {
    tableLock.lock();
    try {
      optimizingRunner.recover();
      arbitraryRunnerMap.values().forEach(runner -> printRecoveryIfNecessary(runner.recover()));
    } finally {
      tableLock.unlock();
    }
  }

  private void printRecoveryIfNecessary(AmoroProcess<?> process) {
    if (process != null) {
      LOG.info(
          "Recover process {} for action {} of table {}",
          process,
          process.getAction(),
          tableIdentifier);
    }
  }

  public TableBlockerRuntime getBlockerRuntime() {
    return blockerRuntime;
  }

  public DefaultOptimizingState getDefaultOptimizingState() {
    return optimizingState;
  }

  @Override
  public List<OptimizingState> getOptimizingStates() {
    return optimizingRunner.getStates();
  }

  @Override
  public List<TableState> getArbitraryStates() {
    return arbitraryRunnerMap.values().stream()
        .flatMap(runner -> runner.getStates().stream())
        .collect(Collectors.toList());
  }

  public AmoroTable<?> loadTable() {
    return tableManager.loadTable(tableIdentifier);
  }

  public void dispose() {
    arbitraryRunnerMap.values().forEach(SingletonActionRunner::close);
    doAs(TableMetaMapper.class, mapper -> mapper.deleteOptimizingRuntime(tableIdentifier.getId()));
  }

  public void refresh(PendingInput pendingInput, Map<String, String> config) {
    doAsTransaction(
        () -> {
          if (pendingInput != null) {
            optimizingState.savePendingInput(pendingInput);
          }
          TableConfiguration newConfig = TableConfigurations.parseConfig(config);
          if (!tableConfiguration.equals(newConfig)) {
            doAs(
                TableMetaMapper.class,
                mapper ->
                    mapper.updateTableConfiguration(tableIdentifier.getId(), tableConfiguration));
            tableConfiguration = newConfig;
          }
        });
  }

  private void persistTableRuntime() {
    doAs(TableMetaMapper.class, mapper -> mapper.insertTableRuntime(this));
  }

  @Override
  public ServerTableIdentifier getTableIdentifier() {
    return tableIdentifier;
  }

  @Override
  public TableConfiguration getTableConfiguration() {
    return tableConfiguration;
  }

  @Override
  public int getMaxExecuteRetryCount() {
    return tableConfiguration.getOptimizingConfig().getMaxExecuteRetryCount();
  }

  public String getOptimizerGroup() {
    return tableConfiguration.getOptimizingConfig().getOptimizerGroup();
  }

  public long getNewestProcessId() {
    return optimizingState.getId();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("tableIdentifier", tableIdentifier)
        .add("tableConfiguration", tableConfiguration)
        .toString();
  }

  public void setTargetQuota(double targetQuota) {
    optimizingState.setTargetQuota(targetQuota);
  }

  private class SingletonActionRunner<T extends ProcessState> {

    private final List<AmoroProcess<T>> externalProcesses =
        Collections.synchronizedList(Lists.newArrayList());
    private final Lock lock = new ReentrantLock();
    private final Action action;
    private volatile ProcessFactory<T> defaultProcessFactory;
    private volatile AmoroProcess<T> process;
    private volatile long lastTriggerTime;
    private volatile long lastCompletedTime;
    private int retryCount;

    // TODO init time and retry count from sysdb
    public SingletonActionRunner(Action action) {
      this.action = action;
    }

    public void install(ProcessFactory<T> defaultProcessFactory) {
      this.defaultProcessFactory = defaultProcessFactory;
    }

    public AmoroProcess<T> recover() {
      // TODO init state
      process = defaultProcessFactory.recover(DefaultTableRuntime.this, action, null);
      if (process != null) {
        lastTriggerTime = process.getStartTime();
        process.whenCompleted(
            () -> {
              if (process.getStatus() == ProcessStatus.SUCCESS) {
                lastCompletedTime = System.currentTimeMillis();
              } else if (process.getStatus() == ProcessStatus.FAILED) {
                retryCount++;
              }
              process = null;
            });
      }
      process.submit();
      process.getSubmitFuture().join();
      return process;
    }

    public AmoroProcess<T> run() {
      lock.lock();
      try {
        Preconditions.checkState(externalProcesses.isEmpty());
        process = createProcess(defaultProcessFactory);
        if (process != null) {
          lastTriggerTime = process.getStartTime();
          process.whenCompleted(
              () -> {
                if (process.getStatus() == ProcessStatus.SUCCESS) {
                  lastCompletedTime = System.currentTimeMillis();
                } else if (process.getStatus() == ProcessStatus.FAILED) {
                  retryCount++;
                }
                process = null;
              });
          process.submit();
        }
        return process;
      } finally {
        lock.unlock();
      }
    }

    // TODO persist action table
    public AmoroProcess<T> run(ProcessFactory<T> processFactory) {
      lock.lock();
      try {
        Preconditions.checkState(processFactory != defaultProcessFactory);
        AmoroProcess<T> externalProcess = createProcess(processFactory);
        if (externalProcess != null) {
          externalProcesses.add(externalProcess);
          externalProcess.whenCompleted(
              () -> {
                externalProcesses.remove(externalProcess);
              });
          externalProcess.submit();
        }
        return externalProcess;
      } finally {
        lock.unlock();
      }
    }

    private AmoroProcess<T> createProcess(ProcessFactory<T> processFactory) {
      closeDefaultProcess();
      return processFactory.create(DefaultTableRuntime.this, action);
    }

    public long getLastTriggerTime() {
      return lastTriggerTime;
    }

    public long getLastCompletedTime() {
      return lastCompletedTime;
    }

    public int getRetryCount() {
      return retryCount;
    }

    public AmoroProcess<T> getProcess() {
      return process;
    }

    public void closeDefaultProcess() {
      AmoroProcess<T> defaultProcess = this.process;
      if (defaultProcess != null) {
        defaultProcess.close();
      }
    }

    public void close() {
      lock.lock();
      try {
        closeDefaultProcess();
        externalProcesses.forEach(AmoroProcess::close);
      } finally {
        lock.unlock();
      }
    }

    public void close(long processId) {
      lock.lock();
      try {
        if (process != null && process.getId() == processId) {
          closeDefaultProcess();
        } else {
          externalProcesses.stream()
              .filter(p -> p.getId() == processId)
              .findFirst()
              .ifPresent(AmoroProcess::close);
        }
      } finally {
        lock.unlock();
      }
    }

    public List<T> getStates() {
      AmoroProcess<T> process = this.process;
      if (process != null) {
        return Lists.newArrayList(process.getState());
      } else {
        return getExternalStates();
      }
    }

    private List<T> getExternalStates() {
      lock.lock();
      try {
        return externalProcesses.stream().map(AmoroProcess::getState).collect(Collectors.toList());
      } finally {
        lock.unlock();
      }
    }
  }
}
