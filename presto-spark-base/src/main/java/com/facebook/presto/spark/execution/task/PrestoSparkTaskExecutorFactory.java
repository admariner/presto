/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spark.execution.task;

import com.facebook.airlift.json.Codec;
import com.facebook.airlift.json.JsonCodec;
import com.facebook.airlift.log.Logger;
import com.facebook.airlift.stats.TestingGcMonitor;
import com.facebook.presto.Session;
import com.facebook.presto.SessionRepresentation;
import com.facebook.presto.common.RuntimeStats;
import com.facebook.presto.common.block.BlockEncodingManager;
import com.facebook.presto.common.io.DataOutput;
import com.facebook.presto.event.SplitMonitor;
import com.facebook.presto.execution.ExecutionFailureInfo;
import com.facebook.presto.execution.MemoryRevokingSchedulerUtils;
import com.facebook.presto.execution.ScheduledSplit;
import com.facebook.presto.execution.StageExecutionId;
import com.facebook.presto.execution.StageId;
import com.facebook.presto.execution.TaskId;
import com.facebook.presto.execution.TaskInfo;
import com.facebook.presto.execution.TaskManagerConfig;
import com.facebook.presto.execution.TaskSource;
import com.facebook.presto.execution.TaskState;
import com.facebook.presto.execution.TaskStateMachine;
import com.facebook.presto.execution.TaskStatus;
import com.facebook.presto.execution.buffer.OutputBufferInfo;
import com.facebook.presto.execution.buffer.OutputBufferMemoryManager;
import com.facebook.presto.execution.executor.TaskExecutor;
import com.facebook.presto.memory.MemoryPool;
import com.facebook.presto.memory.NodeMemoryConfig;
import com.facebook.presto.memory.QueryContext;
import com.facebook.presto.memory.TraversingQueryContextVisitor;
import com.facebook.presto.memory.VoidTraversingQueryContextVisitor;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.metadata.SessionPropertyManager;
import com.facebook.presto.operator.OperatorContext;
import com.facebook.presto.operator.OutputFactory;
import com.facebook.presto.operator.TaskContext;
import com.facebook.presto.operator.TaskMemoryReservationSummary;
import com.facebook.presto.operator.TaskStats;
import com.facebook.presto.spark.BasicPrincipal;
import com.facebook.presto.spark.PrestoSparkConfig;
import com.facebook.presto.spark.PrestoSparkTaskDescriptor;
import com.facebook.presto.spark.accesscontrol.PrestoSparkAuthenticatorProvider;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkTaskExecutor;
import com.facebook.presto.spark.classloader_interface.IPrestoSparkTaskExecutorFactory;
import com.facebook.presto.spark.classloader_interface.MutablePartitionId;
import com.facebook.presto.spark.classloader_interface.PrestoSparkJavaExecutionTaskInputs;
import com.facebook.presto.spark.classloader_interface.PrestoSparkMutableRow;
import com.facebook.presto.spark.classloader_interface.PrestoSparkSerializedPage;
import com.facebook.presto.spark.classloader_interface.PrestoSparkShuffleStats;
import com.facebook.presto.spark.classloader_interface.PrestoSparkStorageHandle;
import com.facebook.presto.spark.classloader_interface.PrestoSparkTaskInputs;
import com.facebook.presto.spark.classloader_interface.PrestoSparkTaskOutput;
import com.facebook.presto.spark.classloader_interface.SerializedPrestoSparkTaskDescriptor;
import com.facebook.presto.spark.classloader_interface.SerializedPrestoSparkTaskSource;
import com.facebook.presto.spark.classloader_interface.SerializedTaskInfo;
import com.facebook.presto.spark.execution.PrestoSparkBroadcastTableCacheManager;
import com.facebook.presto.spark.execution.PrestoSparkBufferedSerializedPage;
import com.facebook.presto.spark.execution.PrestoSparkExecutionExceptionFactory;
import com.facebook.presto.spark.execution.PrestoSparkOutputBuffer;
import com.facebook.presto.spark.execution.PrestoSparkPageOutputOperator.PrestoSparkPageOutputFactory;
import com.facebook.presto.spark.execution.PrestoSparkRemoteSourceFactory;
import com.facebook.presto.spark.execution.PrestoSparkRowBatch;
import com.facebook.presto.spark.execution.PrestoSparkRowBatch.RowTupleSupplier;
import com.facebook.presto.spark.execution.PrestoSparkRowOutputOperator.PreDeterminedPartitionFunction;
import com.facebook.presto.spark.execution.PrestoSparkRowOutputOperator.PrestoSparkRowOutputFactory;
import com.facebook.presto.spark.execution.shuffle.PrestoSparkShuffleInput;
import com.facebook.presto.spark.execution.shuffle.PrestoSparkShuffleReadInfo;
import com.facebook.presto.spark.execution.shuffle.PrestoSparkShuffleWriteInfo;
import com.facebook.presto.spark.util.PrestoSparkStatsCollectionUtils;
import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.memory.MemoryPoolId;
import com.facebook.presto.spi.page.PageDataOutput;
import com.facebook.presto.spi.plan.PlanFragmentId;
import com.facebook.presto.spi.plan.PlanNodeId;
import com.facebook.presto.spi.security.Identity;
import com.facebook.presto.spi.security.TokenAuthenticator;
import com.facebook.presto.spi.storage.TempDataOperationContext;
import com.facebook.presto.spi.storage.TempDataSink;
import com.facebook.presto.spi.storage.TempStorage;
import com.facebook.presto.spi.storage.TempStorageHandle;
import com.facebook.presto.spiller.NodeSpillConfig;
import com.facebook.presto.spiller.SpillSpaceTracker;
import com.facebook.presto.sql.analyzer.FeaturesConfig;
import com.facebook.presto.sql.planner.LocalExecutionPlanner;
import com.facebook.presto.sql.planner.LocalExecutionPlanner.LocalExecutionPlan;
import com.facebook.presto.sql.planner.OutputPartitioning;
import com.facebook.presto.sql.planner.PlanFragment;
import com.facebook.presto.sql.planner.plan.RemoteSourceNode;
import com.facebook.presto.sql.planner.planPrinter.PlanPrinter;
import com.facebook.presto.storage.TempStorageManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.airlift.units.DataSize;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.util.CollectionAccumulator;
import scala.Tuple2;
import scala.collection.AbstractIterator;
import scala.collection.Iterator;

import javax.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

import static com.facebook.presto.ExceededMemoryLimitException.exceededLocalTotalMemoryLimit;
import static com.facebook.presto.SystemSessionProperties.QUERY_MAX_MEMORY_PER_NODE;
import static com.facebook.presto.SystemSessionProperties.QUERY_MAX_REVOCABLE_MEMORY_PER_NODE;
import static com.facebook.presto.SystemSessionProperties.QUERY_MAX_TOTAL_MEMORY_PER_NODE;
import static com.facebook.presto.SystemSessionProperties.getHashPartitionCount;
import static com.facebook.presto.SystemSessionProperties.getHeapDumpFileDirectory;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxBroadcastMemory;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxMemoryPerNode;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxRevocableMemoryPerNode;
import static com.facebook.presto.SystemSessionProperties.getQueryMaxTotalMemoryPerNode;
import static com.facebook.presto.SystemSessionProperties.isHeapDumpOnExceededMemoryLimitEnabled;
import static com.facebook.presto.SystemSessionProperties.isSpillEnabled;
import static com.facebook.presto.SystemSessionProperties.isVerboseExceededMemoryLimitErrorsEnabled;
import static com.facebook.presto.execution.TaskState.FAILED;
import static com.facebook.presto.execution.TaskStatus.STARTING_VERSION;
import static com.facebook.presto.execution.buffer.BufferState.FINISHED;
import static com.facebook.presto.spark.PrestoSparkSessionProperties.getAttemptNumberToApplyDynamicMemoryPoolTuning;
import static com.facebook.presto.spark.PrestoSparkSessionProperties.getDynamicPrestoMemoryPoolTuningFraction;
import static com.facebook.presto.spark.PrestoSparkSessionProperties.getMemoryRevokingTarget;
import static com.facebook.presto.spark.PrestoSparkSessionProperties.getMemoryRevokingThreshold;
import static com.facebook.presto.spark.PrestoSparkSessionProperties.getShuffleOutputTargetAverageRowSize;
import static com.facebook.presto.spark.PrestoSparkSessionProperties.getSparkBroadcastJoinMaxMemoryOverride;
import static com.facebook.presto.spark.PrestoSparkSessionProperties.getStorageBasedBroadcastJoinWriteBufferSize;
import static com.facebook.presto.spark.PrestoSparkSessionProperties.isDynamicPrestoMemoryPoolTuningEnabled;
import static com.facebook.presto.spark.classloader_interface.PrestoSparkShuffleStats.Operation.WRITE;
import static com.facebook.presto.spark.util.PrestoSparkUtils.deserializeZstdCompressed;
import static com.facebook.presto.spark.util.PrestoSparkUtils.getNullifyingIterator;
import static com.facebook.presto.spark.util.PrestoSparkUtils.serializeZstdCompressed;
import static com.facebook.presto.spark.util.PrestoSparkUtils.toPrestoSparkSerializedPage;
import static com.facebook.presto.spi.ErrorCause.UNKNOWN;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.FIXED_ARBITRARY_DISTRIBUTION;
import static com.facebook.presto.util.Failures.toFailures;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Throwables.propagateIfPossible;
import static com.google.common.collect.Iterables.getFirst;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.airlift.units.DataSize.succinctBytes;
import static java.lang.Math.min;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;

public class PrestoSparkTaskExecutorFactory
        implements IPrestoSparkTaskExecutorFactory
{
    private static final Logger log = Logger.get(PrestoSparkTaskExecutorFactory.class);

    private final SessionPropertyManager sessionPropertyManager;
    private final BlockEncodingManager blockEncodingManager;
    private final FunctionAndTypeManager functionAndTypeManager;

    private final JsonCodec<PrestoSparkTaskDescriptor> taskDescriptorJsonCodec;
    private final Codec<TaskSource> taskSourceCodec;
    private final Codec<TaskInfo> taskInfoCodec;
    private final JsonCodec<List<TaskMemoryReservationSummary>> memoryReservationSummaryJsonCodec;

    private final Executor notificationExecutor;
    private final ScheduledExecutorService yieldExecutor;
    private final ScheduledExecutorService memoryUpdateExecutor;
    private final ExecutorService memoryRevocationExecutor;

    private final LocalExecutionPlanner localExecutionPlanner;
    private final PrestoSparkExecutionExceptionFactory executionExceptionFactory;
    private final TaskExecutor taskExecutor;
    private final SplitMonitor splitMonitor;

    private final Set<PrestoSparkAuthenticatorProvider> authenticatorProviders;

    private final NodeMemoryConfig nodeMemoryConfig;

    private final boolean nativeExecution;

    private final DataSize maxQuerySpillPerNode;
    private final DataSize sinkMaxBufferSize;

    private final boolean perOperatorCpuTimerEnabled;
    private final boolean cpuTimerEnabled;

    private final boolean perOperatorAllocationTrackingEnabled;
    private final boolean allocationTrackingEnabled;
    private final TempStorageManager tempStorageManager;
    private final PrestoSparkBroadcastTableCacheManager prestoSparkBroadcastTableCacheManager;
    private final String storageBasedBroadcastJoinStorage;

    private final AtomicBoolean memoryRevokePending = new AtomicBoolean();
    private final AtomicBoolean memoryRevokeRequestInProgress = new AtomicBoolean();

    @Inject
    public PrestoSparkTaskExecutorFactory(
            SessionPropertyManager sessionPropertyManager,
            BlockEncodingManager blockEncodingManager,
            FunctionAndTypeManager functionAndTypeManager,
            JsonCodec<PrestoSparkTaskDescriptor> taskDescriptorJsonCodec,
            Codec<TaskSource> taskSourceCodec,
            Codec<TaskInfo> taskInfoCodec,
            JsonCodec<List<TaskMemoryReservationSummary>> memoryReservationSummaryJsonCodec,
            Executor notificationExecutor,
            ScheduledExecutorService yieldExecutor,
            ScheduledExecutorService memoryUpdateExecutor,
            ExecutorService memoryRevocationExecutor,
            LocalExecutionPlanner localExecutionPlanner,
            PrestoSparkExecutionExceptionFactory executionExceptionFactory,
            TaskExecutor taskExecutor,
            SplitMonitor splitMonitor,
            Set<PrestoSparkAuthenticatorProvider> authenticatorProviders,
            FeaturesConfig featuresConfig,
            TaskManagerConfig taskManagerConfig,
            NodeMemoryConfig nodeMemoryConfig,
            NodeSpillConfig nodeSpillConfig,
            TempStorageManager tempStorageManager,
            PrestoSparkBroadcastTableCacheManager prestoSparkBroadcastTableCacheManager,
            PrestoSparkConfig prestoSparkConfig)
    {
        this(
                sessionPropertyManager,
                blockEncodingManager,
                functionAndTypeManager,
                taskDescriptorJsonCodec,
                taskSourceCodec,
                taskInfoCodec,
                memoryReservationSummaryJsonCodec,
                notificationExecutor,
                yieldExecutor,
                memoryUpdateExecutor,
                memoryRevocationExecutor,
                localExecutionPlanner,
                executionExceptionFactory,
                taskExecutor,
                splitMonitor,
                authenticatorProviders,
                nodeMemoryConfig,
                featuresConfig.isNativeExecutionEnabled(),
                requireNonNull(nodeSpillConfig, "nodeSpillConfig is null").getQueryMaxSpillPerNode(),
                requireNonNull(taskManagerConfig, "taskManagerConfig is null").getSinkMaxBufferSize(),
                requireNonNull(taskManagerConfig, "taskManagerConfig is null").isPerOperatorCpuTimerEnabled(),
                requireNonNull(taskManagerConfig, "taskManagerConfig is null").isTaskCpuTimerEnabled(),
                requireNonNull(taskManagerConfig, "taskManagerConfig is null").isPerOperatorAllocationTrackingEnabled(),
                requireNonNull(taskManagerConfig, "taskManagerConfig is null").isTaskAllocationTrackingEnabled(),
                tempStorageManager,
                requireNonNull(prestoSparkConfig, "prestoSparkConfig is null").getStorageBasedBroadcastJoinStorage(),
                prestoSparkBroadcastTableCacheManager);
    }

    public PrestoSparkTaskExecutorFactory(
            SessionPropertyManager sessionPropertyManager,
            BlockEncodingManager blockEncodingManager,
            FunctionAndTypeManager functionAndTypeManager,
            JsonCodec<PrestoSparkTaskDescriptor> taskDescriptorJsonCodec,
            Codec<TaskSource> taskSourceCodec,
            Codec<TaskInfo> taskInfoCodec,
            JsonCodec<List<TaskMemoryReservationSummary>> memoryReservationSummaryJsonCodec,
            Executor notificationExecutor,
            ScheduledExecutorService yieldExecutor,
            ScheduledExecutorService memoryUpdateExecutor,
            ExecutorService memoryRevocationExecutor,
            LocalExecutionPlanner localExecutionPlanner,
            PrestoSparkExecutionExceptionFactory executionExceptionFactory,
            TaskExecutor taskExecutor,
            SplitMonitor splitMonitor,
            Set<PrestoSparkAuthenticatorProvider> authenticatorProviders,
            NodeMemoryConfig nodeMemoryConfig,
            boolean nativeExecution,
            DataSize maxQuerySpillPerNode,
            DataSize sinkMaxBufferSize,
            boolean perOperatorCpuTimerEnabled,
            boolean cpuTimerEnabled,
            boolean perOperatorAllocationTrackingEnabled,
            boolean allocationTrackingEnabled,
            TempStorageManager tempStorageManager,
            String storageBasedBroadcastJoinStorage,
            PrestoSparkBroadcastTableCacheManager prestoSparkBroadcastTableCacheManager)
    {
        this.sessionPropertyManager = requireNonNull(sessionPropertyManager, "sessionPropertyManager is null");
        this.blockEncodingManager = requireNonNull(blockEncodingManager, "blockEncodingManager is null");
        this.functionAndTypeManager = requireNonNull(functionAndTypeManager, "functionManager is null");
        this.taskDescriptorJsonCodec = requireNonNull(taskDescriptorJsonCodec, "sparkTaskDescriptorJsonCodec is null");
        this.taskSourceCodec = requireNonNull(taskSourceCodec, "taskSourceCodec is null");
        this.taskInfoCodec = requireNonNull(taskInfoCodec, "taskInfoCodec is null");
        this.memoryReservationSummaryJsonCodec = requireNonNull(memoryReservationSummaryJsonCodec, "memoryReservationSummaryJsonCodec is null");
        this.notificationExecutor = requireNonNull(notificationExecutor, "notificationExecutor is null");
        this.yieldExecutor = requireNonNull(yieldExecutor, "yieldExecutor is null");
        this.memoryUpdateExecutor = requireNonNull(memoryUpdateExecutor, "memoryUpdateExecutor is null");
        this.memoryRevocationExecutor = requireNonNull(memoryRevocationExecutor, "memoryRevocationExecutor is null");
        this.localExecutionPlanner = requireNonNull(localExecutionPlanner, "localExecutionPlanner is null");
        this.executionExceptionFactory = requireNonNull(executionExceptionFactory, "executionExceptionFactory is null");
        this.taskExecutor = requireNonNull(taskExecutor, "taskExecutor is null");
        this.splitMonitor = requireNonNull(splitMonitor, "splitMonitor is null");
        this.authenticatorProviders = ImmutableSet.copyOf(requireNonNull(authenticatorProviders, "authenticatorProviders is null"));
        // Ordering is needed to make sure serialized plans are consistent for the same map
        this.nodeMemoryConfig = requireNonNull(nodeMemoryConfig, "nodeMemoryConfig is null");
        this.nativeExecution = nativeExecution;
        this.maxQuerySpillPerNode = requireNonNull(maxQuerySpillPerNode, "maxQuerySpillPerNode is null");
        this.sinkMaxBufferSize = requireNonNull(sinkMaxBufferSize, "sinkMaxBufferSize is null");
        this.perOperatorCpuTimerEnabled = perOperatorCpuTimerEnabled;
        this.cpuTimerEnabled = cpuTimerEnabled;
        this.perOperatorAllocationTrackingEnabled = perOperatorAllocationTrackingEnabled;
        this.allocationTrackingEnabled = allocationTrackingEnabled;
        this.tempStorageManager = requireNonNull(tempStorageManager, "tempStorageManager is null");
        this.storageBasedBroadcastJoinStorage = requireNonNull(storageBasedBroadcastJoinStorage, "storageBasedBroadcastJoinStorage is null");
        this.prestoSparkBroadcastTableCacheManager = requireNonNull(prestoSparkBroadcastTableCacheManager, "prestoSparkBroadcastTableCacheManager is null");
    }

    @Override
    public <T extends PrestoSparkTaskOutput> IPrestoSparkTaskExecutor<T> create(
            int partitionId,
            int attemptNumber,
            SerializedPrestoSparkTaskDescriptor serializedTaskDescriptor,
            Iterator<SerializedPrestoSparkTaskSource> serializedTaskSources,
            PrestoSparkTaskInputs inputs,
            CollectionAccumulator<SerializedTaskInfo> taskInfoCollector,
            CollectionAccumulator<PrestoSparkShuffleStats> shuffleStatsCollector,
            Class<T> outputType)
    {
        try {
            return doCreate(
                    partitionId,
                    attemptNumber,
                    serializedTaskDescriptor,
                    serializedTaskSources,
                    inputs,
                    taskInfoCollector,
                    shuffleStatsCollector,
                    outputType);
        }
        catch (RuntimeException e) {
            throw executionExceptionFactory.toPrestoSparkExecutionException(e);
        }
    }

    @Override
    public void close() {}

    public <T extends PrestoSparkTaskOutput> IPrestoSparkTaskExecutor<T> doCreate(
            int partitionId,
            int attemptNumber,
            SerializedPrestoSparkTaskDescriptor serializedTaskDescriptor,
            Iterator<SerializedPrestoSparkTaskSource> serializedTaskSources,
            PrestoSparkTaskInputs inputs,
            CollectionAccumulator<SerializedTaskInfo> taskInfoCollector,
            CollectionAccumulator<PrestoSparkShuffleStats> shuffleStatsCollector,
            Class<T> outputType)
    {
        PrestoSparkTaskDescriptor taskDescriptor = taskDescriptorJsonCodec.fromJson(serializedTaskDescriptor.getBytes());
        ImmutableMap.Builder<String, TokenAuthenticator> extraAuthenticators = ImmutableMap.builder();
        authenticatorProviders.forEach(provider -> extraAuthenticators.putAll(provider.getTokenAuthenticators()));

        SessionRepresentation sessionRepresentation = taskDescriptor.getSession();
        Session session = sessionRepresentation.toSession(
                sessionPropertyManager,
                taskDescriptor.getExtraCredentials(),
                extraAuthenticators.build());
        DataSize maxUserMemory = getDynamicallyComputedMemory(session, getQueryMaxMemoryPerNode(session), attemptNumber);
        DataSize maxTotalMemory = getDynamicallyComputedMemory(session, getQueryMaxTotalMemoryPerNode(session), attemptNumber);
        DataSize maxRevocableMemory = getDynamicallyComputedMemory(session, getQueryMaxRevocableMemoryPerNode(session), attemptNumber);

        ImmutableMap.Builder<String, String> extraSessionProperties = ImmutableMap.<String, String>builder()
                .put(QUERY_MAX_MEMORY_PER_NODE, maxUserMemory.toString())
                .put(QUERY_MAX_TOTAL_MEMORY_PER_NODE, maxTotalMemory.toString())
                .put(QUERY_MAX_REVOCABLE_MEMORY_PER_NODE, maxRevocableMemory.toString());

        session = createSessionWithExtraSessionProperties(
                sessionRepresentation,
                taskDescriptor.getExtraCredentials(),
                extraAuthenticators.build(),
                extraSessionProperties.build());

        PlanFragment fragment = taskDescriptor.getFragment();
        StageId stageId = new StageId(session.getQueryId(), fragment.getId().getId());

        // Clear the cache if the cache does not have broadcast table for current stageId.
        // We will only cache 1 HT at any time. If the stageId changes, we will drop the old cached HT
        prestoSparkBroadcastTableCacheManager.removeCachedTablesForStagesOtherThan(stageId);

        TaskId taskId = new TaskId(new StageExecutionId(stageId, 0), partitionId, attemptNumber);

        log.info(PlanPrinter.textPlanFragment(fragment, functionAndTypeManager, session, true));

        DataSize maxBroadcastMemory = getSparkBroadcastJoinMaxMemoryOverride(session);
        if (maxBroadcastMemory == null) {
            maxBroadcastMemory = new DataSize(min(nodeMemoryConfig.getMaxQueryBroadcastMemory().toBytes(), getQueryMaxBroadcastMemory(session).toBytes()), BYTE);
        }

        MemoryPool memoryPool = new MemoryPool(new MemoryPoolId("spark-executor-memory-pool"), maxTotalMemory);

        SpillSpaceTracker spillSpaceTracker = new SpillSpaceTracker(maxQuerySpillPerNode);

        QueryContext queryContext = new QueryContext(
                session.getQueryId(),
                maxUserMemory,
                maxTotalMemory,
                maxBroadcastMemory,
                maxRevocableMemory,
                memoryPool,
                new TestingGcMonitor(),
                notificationExecutor,
                yieldExecutor,
                maxQuerySpillPerNode,
                spillSpaceTracker,
                memoryReservationSummaryJsonCodec);
        queryContext.setVerboseExceededMemoryLimitErrorsEnabled(isVerboseExceededMemoryLimitErrorsEnabled(session));
        queryContext.setHeapDumpOnExceededMemoryLimitEnabled(isHeapDumpOnExceededMemoryLimitEnabled(session));
        String heapDumpFilePath = Paths.get(
                getHeapDumpFileDirectory(session),
                format("%s_%s.hprof", session.getQueryId().getId(), stageId.getId())).toString();
        queryContext.setHeapDumpFilePath(heapDumpFilePath);

        TaskStateMachine taskStateMachine = new TaskStateMachine(taskId, notificationExecutor);
        TaskContext taskContext = queryContext.addTaskContext(
                taskStateMachine,
                session,
                // Plan has to be retained only if verbose memory exceeded errors are requested
                isVerboseExceededMemoryLimitErrorsEnabled(session) ? Optional.of(fragment.getRoot()) : Optional.empty(),
                perOperatorCpuTimerEnabled,
                cpuTimerEnabled,
                perOperatorAllocationTrackingEnabled,
                allocationTrackingEnabled,
                false);

        final double memoryRevokingThreshold = getMemoryRevokingThreshold(session);
        final double memoryRevokingTarget = getMemoryRevokingTarget(session);
        checkArgument(
                memoryRevokingTarget <= memoryRevokingThreshold,
                "memoryRevokingTarget should be less than or equal memoryRevokingThreshold, but got %s and %s respectively",
                memoryRevokingTarget, memoryRevokingThreshold);
        boolean heapDumpOnExceededMemoryLimitEnabled = isHeapDumpOnExceededMemoryLimitEnabled(session);
        if (isSpillEnabled(session)) {
            memoryPool.addListener((pool, queryId, totalMemoryReservationBytes) -> {
                if (totalMemoryReservationBytes > queryContext.getPeakNodeTotalMemory()) {
                    queryContext.setPeakNodeTotalMemory(totalMemoryReservationBytes);
                }

                if (totalMemoryReservationBytes > pool.getMaxBytes() * memoryRevokingThreshold && memoryRevokeRequestInProgress.compareAndSet(false, true)) {
                    memoryRevocationExecutor.execute(() -> {
                        try {
                            AtomicLong remainingBytesToRevoke = new AtomicLong(totalMemoryReservationBytes - (long) (memoryRevokingTarget * pool.getMaxBytes()));
                            remainingBytesToRevoke.addAndGet(-MemoryRevokingSchedulerUtils.getMemoryAlreadyBeingRevoked(ImmutableList.of(taskContext), remainingBytesToRevoke.get()));
                            taskContext.accept(new VoidTraversingQueryContextVisitor<AtomicLong>()
                            {
                                @Override
                                public Void visitOperatorContext(OperatorContext operatorContext, AtomicLong remainingBytesToRevoke)
                                {
                                    if (remainingBytesToRevoke.get() > 0) {
                                        long revokedBytes = operatorContext.requestMemoryRevoking();
                                        if (revokedBytes > 0) {
                                            memoryRevokePending.set(true);
                                            remainingBytesToRevoke.addAndGet(-revokedBytes);
                                        }
                                    }
                                    return null;
                                }
                            }, remainingBytesToRevoke);
                            memoryRevokeRequestInProgress.set(false);
                        }
                        catch (Exception e) {
                            log.error(e, "Error requesting memory revoking");
                        }
                    });
                }

                // Get the latest memory reservation info since it might have changed due to revoke
                long totalReservedMemory = pool.getQueryMemoryReservation(queryId) + pool.getQueryRevocableMemoryReservation(queryId);

                // If total memory usage is over maxTotalMemory and memory revoke request is not pending, fail the query with EXCEEDED_MEMORY_LIMIT error
                if (totalReservedMemory > maxTotalMemory.toBytes() && !memoryRevokeRequestInProgress.get() && !isMemoryRevokePending(taskContext)) {
                    throw exceededLocalTotalMemoryLimit(
                            maxTotalMemory,
                            queryContext.getAdditionalFailureInfo(totalReservedMemory, 0, "test-operator") +
                                    format("Total reserved memory: %s, Total revocable memory: %s",
                                            succinctBytes(pool.getQueryMemoryReservation(queryId)),
                                            succinctBytes(pool.getQueryRevocableMemoryReservation(queryId))),
                            heapDumpOnExceededMemoryLimitEnabled,
                            Optional.ofNullable(heapDumpFilePath),
                            UNKNOWN);
                }
            });
        }

        ImmutableMap.Builder<PlanNodeId, List<PrestoSparkShuffleInput>> shuffleInputs = ImmutableMap.builder();
        ImmutableMap.Builder<PlanNodeId, List<java.util.Iterator<PrestoSparkSerializedPage>>> pageInputs = ImmutableMap.builder();
        ImmutableMap.Builder<PlanNodeId, List<?>> broadcastInputs = ImmutableMap.builder();
        ImmutableMap.Builder<PlanNodeId, PrestoSparkShuffleReadInfo> shuffleReadInfos = ImmutableMap.builder();

        List<TaskSource> taskSources;
        Optional<PrestoSparkShuffleWriteInfo> shuffleWriteInfo = Optional.empty();

        checkArgument(
                inputs instanceof PrestoSparkJavaExecutionTaskInputs,
                format("PrestoSparkJavaExecutionTaskInputs is required for java execution, but %s is provided", inputs.getClass().getName()));
        PrestoSparkJavaExecutionTaskInputs taskInputs = (PrestoSparkJavaExecutionTaskInputs) inputs;
        fillJavaExecutionTaskInputs(fragment, taskInputs, shuffleInputs, pageInputs, broadcastInputs);
        taskSources = getTaskSources(serializedTaskSources);

        OutputBufferMemoryManager memoryManager = new OutputBufferMemoryManager(
                sinkMaxBufferSize.toBytes(),
                () -> queryContext.getTaskContextByTaskId(taskId).localSystemMemoryContext(),
                notificationExecutor);

        Optional<OutputPartitioning> preDeterminedPartition = Optional.empty();
        if (fragment.getPartitioningScheme().getPartitioning().getHandle().equals(FIXED_ARBITRARY_DISTRIBUTION)) {
            int partitionCount = getHashPartitionCount(session);
            preDeterminedPartition = Optional.of(new OutputPartitioning(
                    new PreDeterminedPartitionFunction(partitionId % partitionCount, partitionCount),
                    ImmutableList.of(),
                    ImmutableList.of(),
                    false,
                    OptionalInt.empty()));
        }

        TempDataOperationContext tempDataOperationContext = new TempDataOperationContext(
                session.getSource(),
                session.getQueryId().getId(),
                session.getClientInfo(),
                Optional.of(session.getClientTags()),
                session.getIdentity());
        TempStorage tempStorage = tempStorageManager.getTempStorage(storageBasedBroadcastJoinStorage);

        Output<T> output = configureOutput(
                outputType,
                blockEncodingManager,
                memoryManager,
                getShuffleOutputTargetAverageRowSize(session),
                preDeterminedPartition,
                tempStorage,
                tempDataOperationContext,
                getStorageBasedBroadcastJoinWriteBufferSize(session));
        PrestoSparkOutputBuffer<?> outputBuffer = output.getOutputBuffer();

        LocalExecutionPlan localExecutionPlan = localExecutionPlanner.plan(
                taskContext,
                fragment,
                output.getOutputFactory(),
                new PrestoSparkRemoteSourceFactory(
                        blockEncodingManager,
                        shuffleInputs.build(),
                        pageInputs.build(),
                        broadcastInputs.build(),
                        partitionId,
                        shuffleStatsCollector,
                        tempStorage,
                        tempDataOperationContext,
                        prestoSparkBroadcastTableCacheManager,
                        stageId),
                taskDescriptor.getTableWriteInfo(),
                true,
                ImmutableList.of());

        taskStateMachine.addStateChangeListener(state -> {
            if (state.isDone()) {
                outputBuffer.setNoMoreRows();
            }
        });

        PrestoSparkTaskExecution taskExecution = new PrestoSparkTaskExecution(
                taskStateMachine,
                taskContext,
                localExecutionPlan,
                taskExecutor,
                splitMonitor,
                notificationExecutor,
                memoryUpdateExecutor,
                nativeExecution);

        log.info("Task [%s] received %d splits.",
                taskId,
                taskSources.stream()
                        .mapToInt(taskSource -> taskSource.getSplits().size())
                        .sum());

        OptionalLong totalSplitSize = computeAllSplitsSize(taskSources);
        if (totalSplitSize.isPresent()) {
            log.info("Total split size: %s bytes.", totalSplitSize.getAsLong());
        }
        taskExecution.start(taskSources);

        return new PrestoSparkTaskExecutor<>(
                taskContext,
                taskStateMachine,
                output.getOutputSupplier(),
                taskInfoCodec,
                taskInfoCollector,
                shuffleStatsCollector,
                executionExceptionFactory,
                output.getOutputBufferType(),
                outputBuffer,
                tempStorage,
                tempDataOperationContext);
    }

    private DataSize getDynamicallyComputedMemory(Session session, DataSize memory, int attemptNumber)
    {
        // For the first attempt, use the static memory values present in SessionProperties.
        // Second attempt onwards use configured JVM memory for computing presto memory limits.
        if (isDynamicPrestoMemoryPoolTuningEnabled(session) && attemptNumber >= getAttemptNumberToApplyDynamicMemoryPoolTuning(session)) {
            double jvmMaxMemoryInBytes = Runtime.getRuntime().maxMemory();
            double prestoMemoryPoolTuningFraction = getDynamicPrestoMemoryPoolTuningFraction(session);
            log.info("Dynamically Tuning Presto Memory Configs. Configured JVM Memory: %f; Dynamic Memory Tuning fraction: %f", jvmMaxMemoryInBytes, prestoMemoryPoolTuningFraction);
            double finalMemoryValue = Math.max(prestoMemoryPoolTuningFraction * jvmMaxMemoryInBytes, memory.toBytes());
            return DataSize.succinctDataSize(finalMemoryValue, DataSize.Unit.BYTE);
        }

        return memory;
    }

    private Session createSessionWithExtraSessionProperties(
            SessionRepresentation sessionRepresentation,
            Map<String, String> extraCredentials,
            Map<String, TokenAuthenticator> extraAuthenticators,
            Map<String, String> extraSystemProperties)
    {
        Map<String, String> updatedSessionProperties = new HashMap<>(sessionRepresentation.getSystemProperties());
        updatedSessionProperties.putAll(extraSystemProperties);

        return new Session(
                new QueryId(sessionRepresentation.getQueryId()),
                sessionRepresentation.getTransactionId(),
                sessionRepresentation.isClientTransactionSupport(),
                new Identity(
                        sessionRepresentation.getUser(),
                        sessionRepresentation.getPrincipal().map(BasicPrincipal::new),
                        sessionRepresentation.getRoles(),
                        extraCredentials,
                        extraAuthenticators,
                        Optional.empty(),
                        Optional.empty()),
                sessionRepresentation.getSource(),
                sessionRepresentation.getCatalog(),
                sessionRepresentation.getSchema(),
                sessionRepresentation.getTraceToken(),
                sessionRepresentation.getTimeZoneKey(),
                sessionRepresentation.getLocale(),
                sessionRepresentation.getRemoteUserAddress(),
                sessionRepresentation.getUserAgent(),
                sessionRepresentation.getClientInfo(),
                sessionRepresentation.getClientTags(),
                sessionRepresentation.getResourceEstimates(),
                sessionRepresentation.getStartTime(),
                ImmutableMap.copyOf(updatedSessionProperties),
                sessionRepresentation.getCatalogProperties(),
                sessionRepresentation.getUnprocessedCatalogProperties(),
                sessionPropertyManager,
                sessionRepresentation.getPreparedStatements(),
                sessionRepresentation.getSessionFunctions(),
                Optional.empty(),
                // we use NOOP to create a session from the representation as worker does not require warning collectors
                WarningCollector.NOOP,
                new RuntimeStats(),
                Optional.empty());
    }

    public boolean isMemoryRevokePending(TaskContext taskContext)
    {
        TraversingQueryContextVisitor<Void, Boolean> visitor = new TraversingQueryContextVisitor<Void, Boolean>()
        {
            @Override
            public Boolean visitOperatorContext(OperatorContext operatorContext, Void context)
            {
                return operatorContext.isMemoryRevokingRequested();
            }

            @Override
            public Boolean mergeResults(List<Boolean> childrenResults)
            {
                return childrenResults.contains(true);
            }
        };

        memoryRevocationExecutor.execute(() -> memoryRevokePending.set(taskContext.accept(visitor, null)));
        return memoryRevokePending.get();
    }

    private static OptionalLong computeAllSplitsSize(List<TaskSource> taskSources)
    {
        long sum = 0;
        for (TaskSource taskSource : taskSources) {
            for (ScheduledSplit scheduledSplit : taskSource.getSplits()) {
                ConnectorSplit connectorSplit = scheduledSplit.getSplit().getConnectorSplit();
                if (!connectorSplit.getSplitSizeInBytes().isPresent()) {
                    return OptionalLong.empty();
                }

                sum += connectorSplit.getSplitSizeInBytes().getAsLong();
            }
        }

        return OptionalLong.of(sum);
    }

    private void fillJavaExecutionTaskInputs(
            PlanFragment fragment,
            PrestoSparkJavaExecutionTaskInputs inputs,
            ImmutableMap.Builder<PlanNodeId, List<PrestoSparkShuffleInput>> shuffleInputs,
            ImmutableMap.Builder<PlanNodeId, List<java.util.Iterator<PrestoSparkSerializedPage>>> pageInputs,
            ImmutableMap.Builder<PlanNodeId, List<?>> broadcastInputs)
    {
        for (RemoteSourceNode remoteSource : fragment.getRemoteSourceNodes()) {
            ImmutableList.Builder<PrestoSparkShuffleInput> remoteSourceRowInputsBuilder = ImmutableList.builder();
            ImmutableList.Builder<java.util.Iterator<PrestoSparkSerializedPage>> remoteSourcePageInputsBuilder = ImmutableList.builder();
            ImmutableList.Builder<List<?>> broadcastInputsListBuilder = ImmutableList.builder();
            for (PlanFragmentId sourceFragmentId : remoteSource.getSourceFragmentIds()) {
                Iterator<Tuple2<MutablePartitionId, PrestoSparkMutableRow>> shuffleInput = inputs.getShuffleInputs().get(sourceFragmentId.toString());
                Broadcast<?> broadcastInput = inputs.getBroadcastInputs().get(sourceFragmentId.toString());
                List<PrestoSparkSerializedPage> inMemoryInput = inputs.getInMemoryInputs().get(sourceFragmentId.toString());

                if (shuffleInput != null) {
                    checkArgument(broadcastInput == null, "single remote source is not expected to accept different kind of inputs");
                    checkArgument(inMemoryInput == null, "single remote source is not expected to accept different kind of inputs");
                    remoteSourceRowInputsBuilder.add(new PrestoSparkShuffleInput(sourceFragmentId.getId(), shuffleInput));
                    continue;
                }

                if (broadcastInput != null) {
                    checkArgument(inMemoryInput == null, "single remote source is not expected to accept different kind of inputs");
                    // TODO: Enable NullifyingIterator once migrated to one task per JVM model
                    // NullifyingIterator removes element from the list upon return
                    // This allows GC to gradually reclaim memory
                    // remoteSourcePageInputs.add(getNullifyingIterator(broadcastInput.value()));
                    broadcastInputsListBuilder.add((List<?>) broadcastInput.value());
                    continue;
                }

                if (inMemoryInput != null) {
                    // for in-memory inputs pages can be released incrementally to save memory
                    remoteSourcePageInputsBuilder.add(getNullifyingIterator(inMemoryInput));
                    continue;
                }

                throw new IllegalStateException("Input not found for sourceFragmentId: " + sourceFragmentId);
            }
            List<PrestoSparkShuffleInput> remoteSourceRowInputs = remoteSourceRowInputsBuilder.build();
            List<java.util.Iterator<PrestoSparkSerializedPage>> remoteSourcePageInputs = remoteSourcePageInputsBuilder.build();
            List<List<?>> broadcastInputsList = broadcastInputsListBuilder.build();
            if (!remoteSourceRowInputs.isEmpty()) {
                shuffleInputs.put(remoteSource.getId(), remoteSourceRowInputs);
            }
            if (!remoteSourcePageInputs.isEmpty()) {
                pageInputs.put(remoteSource.getId(), remoteSourcePageInputs);
            }
            if (!broadcastInputsList.isEmpty()) {
                broadcastInputs.put(remoteSource.getId(), broadcastInputsList);
            }
        }
    }

    private List<TaskSource> getTaskSources(Iterator<SerializedPrestoSparkTaskSource> serializedTaskSources)
    {
        long totalSerializedSizeInBytes = 0;
        ImmutableList.Builder<TaskSource> result = ImmutableList.builder();
        while (serializedTaskSources.hasNext()) {
            SerializedPrestoSparkTaskSource serializedTaskSource = serializedTaskSources.next();
            totalSerializedSizeInBytes += serializedTaskSource.getBytes().length;
            result.add(deserializeZstdCompressed(taskSourceCodec, serializedTaskSource.getBytes()));
        }
        log.info("Total serialized size of all task sources: %s", succinctBytes(totalSerializedSizeInBytes));
        return result.build();
    }

    @SuppressWarnings("unchecked")
    private static <T extends PrestoSparkTaskOutput> Output<T> configureOutput(
            Class<T> outputType,
            BlockEncodingManager blockEncodingManager,
            OutputBufferMemoryManager memoryManager,
            DataSize targetAverageRowSize,
            Optional<OutputPartitioning> preDeterminedPartition,
            TempStorage tempStorage,
            TempDataOperationContext tempDataOperationContext,
            DataSize writeBufferSize)
    {
        if (outputType.equals(PrestoSparkMutableRow.class)) {
            PrestoSparkOutputBuffer<PrestoSparkRowBatch> outputBuffer = new PrestoSparkOutputBuffer<>(memoryManager);
            OutputFactory outputFactory = new PrestoSparkRowOutputFactory(outputBuffer, targetAverageRowSize, preDeterminedPartition);
            OutputSupplier<T> outputSupplier = (OutputSupplier<T>) new RowOutputSupplier(outputBuffer);
            return new Output<>(OutputBufferType.SPARK_ROW_OUTPUT_BUFFER, outputBuffer, outputFactory, outputSupplier);
        }
        else if (outputType.equals(PrestoSparkSerializedPage.class)) {
            PrestoSparkOutputBuffer<PrestoSparkBufferedSerializedPage> outputBuffer = new PrestoSparkOutputBuffer<>(memoryManager);
            OutputFactory outputFactory = new PrestoSparkPageOutputFactory(outputBuffer, blockEncodingManager);
            OutputSupplier<T> outputSupplier = (OutputSupplier<T>) new PageOutputSupplier(outputBuffer);
            return new Output<>(OutputBufferType.SPARK_PAGE_OUTPUT_BUFFER, outputBuffer, outputFactory, outputSupplier);
        }
        else if (outputType.equals(PrestoSparkStorageHandle.class)) {
            PrestoSparkOutputBuffer<PrestoSparkBufferedSerializedPage> outputBuffer = new PrestoSparkOutputBuffer<>(memoryManager);
            OutputFactory outputFactory = new PrestoSparkPageOutputFactory(outputBuffer, blockEncodingManager);
            OutputSupplier<T> outputSupplier = (OutputSupplier<T>) new DiskPageOutputSupplier(outputBuffer, tempStorage, tempDataOperationContext, writeBufferSize);
            return new Output<>(OutputBufferType.SPARK_DISK_PAGE_OUTPUT_BUFFER, outputBuffer, outputFactory, outputSupplier);
        }
        else {
            throw new IllegalArgumentException("Unexpected output type: " + outputType.getName());
        }
    }

    private static class PrestoSparkTaskExecutor<T extends PrestoSparkTaskOutput>
            extends AbstractIterator<Tuple2<MutablePartitionId, T>>
            implements IPrestoSparkTaskExecutor<T>
    {
        private final TaskContext taskContext;
        private final TaskStateMachine taskStateMachine;
        private final OutputSupplier<T> outputSupplier;
        private final Codec<TaskInfo> taskInfoCodec;
        private final CollectionAccumulator<SerializedTaskInfo> taskInfoCollector;
        private final CollectionAccumulator<PrestoSparkShuffleStats> shuffleStatsCollector;
        private final PrestoSparkExecutionExceptionFactory executionExceptionFactory;
        private final OutputBufferType outputBufferType;
        private final PrestoSparkOutputBuffer<?> outputBuffer;
        private final TempStorage tempStorage;
        private final TempDataOperationContext tempDataOperationContext;

        private final UUID taskInstanceId = randomUUID();

        private Tuple2<MutablePartitionId, T> next;

        private Long start;
        private long processedRows;
        private long processedRowBatches;
        private long processedBytes;

        private PrestoSparkTaskExecutor(
                TaskContext taskContext,
                TaskStateMachine taskStateMachine,
                OutputSupplier<T> outputSupplier,
                Codec<TaskInfo> taskInfoCodec,
                CollectionAccumulator<SerializedTaskInfo> taskInfoCollector,
                CollectionAccumulator<PrestoSparkShuffleStats> shuffleStatsCollector,
                PrestoSparkExecutionExceptionFactory executionExceptionFactory,
                OutputBufferType outputBufferType,
                PrestoSparkOutputBuffer<?> outputBuffer,
                TempStorage tempStorage,
                TempDataOperationContext tempDataOperationContext)
        {
            this.taskContext = requireNonNull(taskContext, "taskContext is null");
            this.taskStateMachine = requireNonNull(taskStateMachine, "taskStateMachine is null");
            this.outputSupplier = requireNonNull(outputSupplier, "outputSupplier is null");
            this.taskInfoCodec = requireNonNull(taskInfoCodec, "taskInfoCodec is null");
            this.taskInfoCollector = requireNonNull(taskInfoCollector, "taskInfoCollector is null");
            this.shuffleStatsCollector = requireNonNull(shuffleStatsCollector, "shuffleStatsCollector is null");
            this.executionExceptionFactory = requireNonNull(executionExceptionFactory, "executionExceptionFactory is null");
            this.outputBufferType = requireNonNull(outputBufferType, "outputBufferType is null");
            this.outputBuffer = requireNonNull(outputBuffer, "outputBuffer is null");
            this.tempStorage = requireNonNull(tempStorage, "tempStorage is null");
            this.tempDataOperationContext = requireNonNull(tempDataOperationContext, "tempDataOperationContext is null");
        }

        @Override
        public boolean hasNext()
        {
            if (next == null) {
                next = computeNext();
            }
            return next != null;
        }

        @Override
        public Tuple2<MutablePartitionId, T> next()
        {
            if (next == null) {
                next = computeNext();
            }
            if (next == null) {
                throw new NoSuchElementException();
            }
            Tuple2<MutablePartitionId, T> result = next;
            next = null;
            return result;
        }

        protected Tuple2<MutablePartitionId, T> computeNext()
        {
            try {
                return doComputeNext();
            }
            catch (RuntimeException e) {
                throw executionExceptionFactory.toPrestoSparkExecutionException(e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                taskStateMachine.abort();
                throw new RuntimeException(e);
            }
        }

        private Tuple2<MutablePartitionId, T> doComputeNext()
                throws InterruptedException
        {
            if (start == null) {
                start = System.currentTimeMillis();
            }
            Tuple2<MutablePartitionId, T> output = outputSupplier.getNext();

            if (output != null) {
                processedRows += output._2.getPositionCount();
                processedRowBatches++;
                processedBytes += output._2.getSize();
                return output;
            }

            // task finished
            TaskState taskState = taskStateMachine.getState();
            checkState(taskState.isDone(), "task is expected to be done");

            collectTaskStatsOnCompletion();

            LinkedBlockingQueue<Throwable> failures = taskStateMachine.getFailureCauses();
            if (failures.isEmpty()) {
                return null;
            }

            Throwable failure = getFirst(failures, null);
            // Delete the storage file, if task is not successful
            if (outputSupplier instanceof DiskPageOutputSupplier && output != null) {
                PrestoSparkStorageHandle sparkStorageHandle = (PrestoSparkStorageHandle) output._2;
                TempStorageHandle tempStorageHandle = tempStorage.deserialize(sparkStorageHandle.getSerializedStorageHandle());
                try {
                    tempStorage.remove(tempDataOperationContext, tempStorageHandle);
                    log.info("Removed broadcast spill file: " + tempStorageHandle.toString());
                }
                catch (IOException e) {
                    // self suppression is not allowed
                    if (e != failure) {
                        failure.addSuppressed(e);
                    }
                }
            }

            propagateIfPossible(failure, Error.class);
            propagateIfPossible(failure, RuntimeException.class);
            propagateIfPossible(failure, InterruptedException.class);
            throw new RuntimeException(failure);
        }

        private void collectTaskStatsOnCompletion()
        {
            TaskInfo taskInfo = createTaskInfo(taskContext, taskStateMachine, taskInstanceId, outputBufferType, outputBuffer);
            SerializedTaskInfo serializedTaskInfo = new SerializedTaskInfo(serializeZstdCompressed(taskInfoCodec, taskInfo));
            taskInfoCollector.add(serializedTaskInfo);

            PrestoSparkStatsCollectionUtils.collectMetrics(taskInfo);

            long end = System.currentTimeMillis();
            PrestoSparkShuffleStats shuffleStats = new PrestoSparkShuffleStats(
                    taskContext.getTaskId().getStageExecutionId().getStageId().getId(),
                    taskContext.getTaskId().getId(),
                    WRITE,
                    processedRows,
                    processedRowBatches,
                    processedBytes,
                    end - start - outputSupplier.getTimeSpentWaitingForOutputInMillis());
            shuffleStatsCollector.add(shuffleStats);
        }

        private static TaskInfo createTaskInfo(
                TaskContext taskContext,
                TaskStateMachine taskStateMachine,
                UUID taskInstanceId,
                OutputBufferType outputBufferType,
                PrestoSparkOutputBuffer<?> outputBuffer)
        {
            TaskId taskId = taskContext.getTaskId();
            TaskState taskState = taskContext.getState();
            TaskStats taskStats = taskContext.getTaskStats().summarizeFinal();

            List<ExecutionFailureInfo> failures = ImmutableList.of();
            if (taskState == FAILED) {
                failures = toFailures(taskStateMachine.getFailureCauses());
            }

            TaskStatus taskStatus = new TaskStatus(
                    taskInstanceId.getLeastSignificantBits(),
                    taskInstanceId.getMostSignificantBits(),
                    STARTING_VERSION,
                    taskState,
                    URI.create("http://fake.invalid/task/" + taskId),
                    taskContext.getCompletedDriverGroups(),
                    failures,
                    taskStats.getQueuedPartitionedDrivers(),
                    taskStats.getRunningPartitionedDrivers(),
                    0,
                    false,
                    taskStats.getPhysicalWrittenDataSizeInBytes(),
                    taskStats.getUserMemoryReservationInBytes(),
                    taskStats.getSystemMemoryReservationInBytes(),
                    taskStats.getPeakNodeTotalMemoryInBytes(),
                    taskStats.getFullGcCount(),
                    taskStats.getFullGcTimeInMillis(),
                    taskStats.getTotalCpuTimeInNanos(),
                    System.currentTimeMillis() - taskStats.getCreateTimeInMillis(),
                    taskStats.getQueuedPartitionedSplitsWeight(),
                    taskStats.getRunningPartitionedSplitsWeight());

            OutputBufferInfo outputBufferInfo = new OutputBufferInfo(
                    outputBufferType.name(),
                    FINISHED,
                    false,
                    false,
                    0,
                    0,
                    outputBuffer.getTotalRowsProcessed(),
                    outputBuffer.getTotalPagesProcessed(),
                    ImmutableList.of());

            return new TaskInfo(
                    taskId,
                    taskStatus,
                    System.currentTimeMillis(),
                    outputBufferInfo,
                    ImmutableSet.of(),
                    taskStats,
                    false,
                    "");
        }
    }

    private static class Output<T extends PrestoSparkTaskOutput>
    {
        private final OutputBufferType outputBufferType;
        private final PrestoSparkOutputBuffer<?> outputBuffer;
        private final OutputFactory outputFactory;
        private final OutputSupplier<T> outputSupplier;

        private Output(
                OutputBufferType outputBufferType,
                PrestoSparkOutputBuffer<?> outputBuffer,
                OutputFactory outputFactory,
                OutputSupplier<T> outputSupplier)
        {
            this.outputBufferType = requireNonNull(outputBufferType, "outputBufferType is null");
            this.outputBuffer = requireNonNull(outputBuffer, "outputBuffer is null");
            this.outputFactory = requireNonNull(outputFactory, "outputFactory is null");
            this.outputSupplier = requireNonNull(outputSupplier, "outputSupplier is null");
        }

        public OutputBufferType getOutputBufferType()
        {
            return outputBufferType;
        }

        public PrestoSparkOutputBuffer<?> getOutputBuffer()
        {
            return outputBuffer;
        }

        public OutputFactory getOutputFactory()
        {
            return outputFactory;
        }

        public OutputSupplier<T> getOutputSupplier()
        {
            return outputSupplier;
        }
    }

    private interface OutputSupplier<T extends PrestoSparkTaskOutput>
    {
        Tuple2<MutablePartitionId, T> getNext()
                throws InterruptedException;

        long getTimeSpentWaitingForOutputInMillis();
    }

    private static class RowOutputSupplier
            implements OutputSupplier<PrestoSparkMutableRow>
    {
        private final PrestoSparkOutputBuffer<PrestoSparkRowBatch> outputBuffer;
        private RowTupleSupplier currentRowTupleSupplier;

        private long timeSpentWaitingForOutputInMillis;

        private RowOutputSupplier(PrestoSparkOutputBuffer<PrestoSparkRowBatch> outputBuffer)
        {
            this.outputBuffer = requireNonNull(outputBuffer, "outputBuffer is null");
        }

        @Override
        public Tuple2<MutablePartitionId, PrestoSparkMutableRow> getNext()
                throws InterruptedException
        {
            Tuple2<MutablePartitionId, PrestoSparkMutableRow> next = null;
            while (next == null) {
                if (currentRowTupleSupplier == null) {
                    long start = System.currentTimeMillis();
                    PrestoSparkRowBatch rowBatch = outputBuffer.get();
                    long end = System.currentTimeMillis();
                    timeSpentWaitingForOutputInMillis += (end - start);
                    if (rowBatch == null) {
                        return null;
                    }
                    currentRowTupleSupplier = rowBatch.createRowTupleSupplier();
                }
                next = currentRowTupleSupplier.getNext();
                if (next == null) {
                    currentRowTupleSupplier = null;
                }
            }
            return next;
        }

        @Override
        public long getTimeSpentWaitingForOutputInMillis()
        {
            return timeSpentWaitingForOutputInMillis;
        }
    }

    private static class PageOutputSupplier
            implements OutputSupplier<PrestoSparkSerializedPage>
    {
        private static final MutablePartitionId DEFAULT_PARTITION = new MutablePartitionId();

        private final PrestoSparkOutputBuffer<PrestoSparkBufferedSerializedPage> outputBuffer;

        private long timeSpentWaitingForOutputInMillis;

        private PageOutputSupplier(PrestoSparkOutputBuffer<PrestoSparkBufferedSerializedPage> outputBuffer)
        {
            this.outputBuffer = requireNonNull(outputBuffer, "outputBuffer is null");
        }

        @Override
        public Tuple2<MutablePartitionId, PrestoSparkSerializedPage> getNext()
                throws InterruptedException
        {
            long start = System.currentTimeMillis();
            PrestoSparkBufferedSerializedPage page = outputBuffer.get();
            long end = System.currentTimeMillis();
            timeSpentWaitingForOutputInMillis += (end - start);
            if (page == null) {
                return null;
            }
            return new Tuple2<>(DEFAULT_PARTITION, toPrestoSparkSerializedPage(page.getSerializedPage()));
        }

        @Override
        public long getTimeSpentWaitingForOutputInMillis()
        {
            return timeSpentWaitingForOutputInMillis;
        }
    }

    private static class DiskPageOutputSupplier
            implements OutputSupplier<PrestoSparkStorageHandle>
    {
        private static final MutablePartitionId DEFAULT_PARTITION = new MutablePartitionId();

        private final PrestoSparkOutputBuffer<PrestoSparkBufferedSerializedPage> outputBuffer;
        private final TempStorage tempStorage;
        private final TempDataOperationContext tempDataOperationContext;
        private final long writeBufferSizeInBytes;

        private TempDataSink tempDataSink;
        private long timeSpentWaitingForOutputInMillis;

        private DiskPageOutputSupplier(PrestoSparkOutputBuffer<PrestoSparkBufferedSerializedPage> outputBuffer,
                TempStorage tempStorage,
                TempDataOperationContext tempDataOperationContext,
                DataSize writeBufferSize)
        {
            this.outputBuffer = requireNonNull(outputBuffer, "outputBuffer is null");
            this.tempStorage = requireNonNull(tempStorage, "tempStorage is null");
            this.tempDataOperationContext = requireNonNull(tempDataOperationContext, "tempDataOperationContext is null");
            this.writeBufferSizeInBytes = requireNonNull(writeBufferSize, "writeBufferSize is null").toBytes();
        }

        @Override
        public Tuple2<MutablePartitionId, PrestoSparkStorageHandle> getNext()
                throws InterruptedException
        {
            long start = System.currentTimeMillis();
            PrestoSparkBufferedSerializedPage page = outputBuffer.get();
            if (page == null) {
                return null;
            }

            long compressedBroadcastSizeInBytes = 0;
            long uncompressedBroadcastSizeInBytes = 0;
            long deserializedBroadcastRetainedSizeInBytes = 0;
            int positionCount = 0;
            CRC32 checksum = new CRC32();
            TempStorageHandle tempStorageHandle;
            IOException ioException = null;
            try {
                this.tempDataSink = tempStorage.create(tempDataOperationContext);
                List<DataOutput> bufferedPages = new ArrayList<>();
                long bufferedBytes = 0;

                while (page != null) {
                    PageDataOutput pageDataOutput = new PageDataOutput(page.getSerializedPage());
                    long writtenSize = pageDataOutput.size();

                    if ((writeBufferSizeInBytes - bufferedBytes) < writtenSize && !bufferedPages.isEmpty()) {
                        tempDataSink.write(bufferedPages);
                        bufferedPages.clear();
                        bufferedBytes = 0;
                    }

                    bufferedPages.add(pageDataOutput);
                    bufferedBytes += writtenSize;
                    compressedBroadcastSizeInBytes += page.getSerializedPage().getSizeInBytes();
                    uncompressedBroadcastSizeInBytes += page.getSerializedPage().getUncompressedSizeInBytes();
                    deserializedBroadcastRetainedSizeInBytes += page.getDeserializedRetainedSizeInBytes();
                    positionCount += page.getPositionCount();
                    Slice slice = page.getSerializedPage().getSlice();
                    checksum.update(slice.byteArray(), slice.byteArrayOffset(), slice.length());
                    page = outputBuffer.get();
                }

                if (!bufferedPages.isEmpty()) {
                    tempDataSink.write(bufferedPages);
                    bufferedPages.clear();
                }

                tempStorageHandle = tempDataSink.commit();
                log.info("Created broadcast spill file: " + tempStorageHandle.toString() + " deserialized size: " + deserializedBroadcastRetainedSizeInBytes);
                PrestoSparkStorageHandle prestoSparkStorageHandle =
                        new PrestoSparkStorageHandle(
                                tempStorage.serializeHandle(tempStorageHandle),
                                uncompressedBroadcastSizeInBytes,
                                compressedBroadcastSizeInBytes,
                                deserializedBroadcastRetainedSizeInBytes,
                                checksum.getValue(),
                                positionCount);
                long end = System.currentTimeMillis();
                timeSpentWaitingForOutputInMillis += (end - start);
                return new Tuple2<>(DEFAULT_PARTITION, prestoSparkStorageHandle);
            }
            catch (IOException e) {
                if (ioException == null) {
                    ioException = e;
                }
                try {
                    if (tempDataSink != null) {
                        tempDataSink.rollback();
                    }
                }
                catch (IOException exception) {
                    if (ioException != exception) {
                        ioException.addSuppressed(exception);
                    }
                }
            }
            finally {
                try {
                    if (tempDataSink != null) {
                        tempDataSink.close();
                    }
                }
                catch (IOException e) {
                    if (ioException == null) {
                        ioException = e;
                    }
                    else if (ioException != e) {
                        ioException.addSuppressed(e);
                    }
                    throw new UncheckedIOException("Unable to dump data to disk: ", ioException);
                }
            }

            throw new UncheckedIOException("Unable to dump data to disk: ", ioException);
        }

        @Override
        public long getTimeSpentWaitingForOutputInMillis()
        {
            return timeSpentWaitingForOutputInMillis;
        }
    }

    private enum OutputBufferType
    {
        SPARK_ROW_OUTPUT_BUFFER,
        SPARK_PAGE_OUTPUT_BUFFER,
        SPARK_DISK_PAGE_OUTPUT_BUFFER,
    }
}
