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
package com.facebook.presto.execution;

import com.facebook.drift.annotations.ThriftConstructor;
import com.facebook.drift.annotations.ThriftField;
import com.facebook.drift.annotations.ThriftStruct;
import com.facebook.presto.execution.buffer.BufferInfo;
import com.facebook.presto.execution.buffer.OutputBufferInfo;
import com.facebook.presto.operator.TaskStats;
import com.facebook.presto.spi.plan.PlanNodeId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
import org.joda.time.DateTime;

import javax.annotation.concurrent.Immutable;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static com.facebook.presto.execution.TaskStatus.initialTaskStatus;
import static com.facebook.presto.execution.buffer.BufferState.OPEN;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.requireNonNull;

@Immutable
@ThriftStruct
public class TaskInfo
{
    private final TaskId taskId;
    private final TaskStatus taskStatus;
    private final long lastHeartbeatInMillis;
    private final OutputBufferInfo outputBuffers;
    private final Set<PlanNodeId> noMoreSplits;
    private final TaskStats stats;
    private final boolean needsPlan;
    private final String nodeId;

    @JsonCreator
    @ThriftConstructor
    public TaskInfo(
            @JsonProperty("taskId") TaskId taskId,
            @JsonProperty("taskStatus") TaskStatus taskStatus,
            @JsonProperty("lastHeartbeatInMillis") long lastHeartbeatInMillis,
            @JsonProperty("outputBuffers") OutputBufferInfo outputBuffers,
            @JsonProperty("noMoreSplits") Set<PlanNodeId> noMoreSplits,
            @JsonProperty("stats") TaskStats stats,
            @JsonProperty("needsPlan") boolean needsPlan,
            @JsonProperty("nodeId") String nodeId)
    {
        this.taskId = requireNonNull(taskId, "taskId is null");
        this.taskStatus = requireNonNull(taskStatus, "taskStatus is null");
        checkArgument(lastHeartbeatInMillis >= 0, "lastHeartbeat is negative");
        this.lastHeartbeatInMillis = lastHeartbeatInMillis;
        this.outputBuffers = requireNonNull(outputBuffers, "outputBuffers is null");
        this.noMoreSplits = requireNonNull(noMoreSplits, "noMoreSplits is null");
        this.stats = requireNonNull(stats, "stats is null");

        this.needsPlan = needsPlan;
        this.nodeId = requireNonNull(nodeId, "nodeId is null");
    }

    @JsonProperty
    @ThriftField(1)
    public TaskId getTaskId()
    {
        return taskId;
    }

    @JsonProperty
    @ThriftField(2)
    public TaskStatus getTaskStatus()
    {
        return taskStatus;
    }

    public DateTime getLastHeartbeat()
    {
        return new DateTime(lastHeartbeatInMillis);
    }

    @JsonProperty
    @ThriftField(3)
    public long getLastHeartbeatInMillis()
    {
        return lastHeartbeatInMillis;
    }

    @JsonProperty
    @ThriftField(4)
    public OutputBufferInfo getOutputBuffers()
    {
        return outputBuffers;
    }

    @JsonProperty
    @ThriftField(5)
    public Set<PlanNodeId> getNoMoreSplits()
    {
        return noMoreSplits;
    }

    @JsonProperty
    @ThriftField(6)
    public TaskStats getStats()
    {
        return stats;
    }

    @JsonProperty
    @ThriftField(7)
    public boolean isNeedsPlan()
    {
        return needsPlan;
    }

    @JsonProperty
    @ThriftField(8)
    public String getNodeId()
    {
        return nodeId;
    }

    public TaskInfo summarize()
    {
        if (taskStatus.getState().isDone()) {
            return new TaskInfo(
                    taskId,
                    taskStatus,
                    lastHeartbeatInMillis,
                    outputBuffers.summarize(),
                    noMoreSplits,
                    stats.summarizeFinal(),
                    needsPlan,
                    nodeId);
        }
        return new TaskInfo(
                taskId,
                taskStatus,
                lastHeartbeatInMillis,
                outputBuffers.summarize(),
                noMoreSplits,
                stats.summarize(),
                needsPlan,
                nodeId);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("taskId", taskId)
                .add("state", taskStatus.getState())
                .toString();
    }

    public static TaskInfo createInitialTask(TaskId taskId, URI location, List<BufferInfo> bufferStates, TaskStats taskStats, String nodeId)
    {
        return new TaskInfo(
                taskId,
                initialTaskStatus(location),
                currentTimeMillis(),
                new OutputBufferInfo("UNINITIALIZED", OPEN, true, true, 0, 0, 0, 0, bufferStates),
                ImmutableSet.of(),
                taskStats,
                true,
                nodeId);
    }

    public TaskInfo withTaskStatus(TaskStatus newTaskStatus)
    {
        return new TaskInfo(taskId, newTaskStatus, lastHeartbeatInMillis, outputBuffers, noMoreSplits, stats, needsPlan, nodeId);
    }
}
