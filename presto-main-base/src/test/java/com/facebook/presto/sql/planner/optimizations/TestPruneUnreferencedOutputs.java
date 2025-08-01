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
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.common.block.SortOrder;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.spi.ConnectorId;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.plan.Assignments;
import com.facebook.presto.spi.plan.DataOrganizationSpecification;
import com.facebook.presto.spi.plan.JoinType;
import com.facebook.presto.spi.plan.Ordering;
import com.facebook.presto.spi.plan.OrderingScheme;
import com.facebook.presto.spi.plan.WindowNode;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.assertions.OptimizerAssert;
import com.facebook.presto.sql.planner.iterative.rule.test.BaseRuleTest;
import com.facebook.presto.sql.planner.iterative.rule.test.RuleTester;
import com.facebook.presto.sql.planner.plan.IndexJoinNode;
import com.facebook.presto.testing.TestingTransactionHandle;
import com.facebook.presto.tpch.TpchColumnHandle;
import com.facebook.presto.tpch.TpchTableHandle;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.Test;

import java.util.Optional;

import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.DoubleType.DOUBLE;
import static com.facebook.presto.common.type.VarcharType.VARCHAR;
import static com.facebook.presto.metadata.MetadataManager.createTestMetadataManager;
import static com.facebook.presto.spi.plan.WindowNode.Frame.BoundType.UNBOUNDED_FOLLOWING;
import static com.facebook.presto.spi.plan.WindowNode.Frame.BoundType.UNBOUNDED_PRECEDING;
import static com.facebook.presto.spi.plan.WindowNode.Frame.WindowType.RANGE;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.except;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.indexJoin;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.intersect;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.output;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.project;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.strictIndexSource;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.strictTableScan;
import static com.facebook.presto.sql.planner.assertions.PlanMatchPattern.values;
import static com.facebook.presto.sql.relational.Expressions.call;
import static com.facebook.presto.tpch.TpchMetadata.TINY_SCALE_FACTOR;

public class TestPruneUnreferencedOutputs
        extends BaseRuleTest
{
    /**
     * Test that the unreferenced output pruning works correctly when WindowNode is pruned as no downstream operators are consuming the window function output
     */
    @Test
    public void windowNodePruning()
    {
        FunctionHandle functionHandle = createTestMetadataManager().getFunctionAndTypeManager().lookupFunction("rank", ImmutableList.of());
        CallExpression call = call("rank", functionHandle, BIGINT);
        WindowNode.Frame frame = new WindowNode.Frame(
                RANGE,
                UNBOUNDED_PRECEDING,
                Optional.empty(),
                Optional.empty(),
                UNBOUNDED_FOLLOWING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertRuleApplication()
                .on(p ->
                        p.output(ImmutableList.of("user_uuid"), ImmutableList.of(p.variable("user_uuid", VARCHAR)),
                                p.project(Assignments.of(p.variable("user_uuid", VARCHAR), p.variable("user_uuid", VARCHAR)),
                                        p.window(
                                                new DataOrganizationSpecification(
                                                        ImmutableList.of(p.variable("user_uuid", VARCHAR)),
                                                        Optional.of(new OrderingScheme(
                                                                ImmutableList.of(
                                                                        new Ordering(p.variable("expr"), SortOrder.ASC_NULLS_LAST),
                                                                        new Ordering(p.variable("random"), SortOrder.ASC_NULLS_LAST))))),
                                                ImmutableMap.of(
                                                        p.variable("rank"),
                                                        new WindowNode.Function(call, frame, false)),
                                                p.project(Assignments.builder()
                                                                .put(p.variable("user_uuid", VARCHAR), p.variable("user_uuid", VARCHAR))
                                                                .put(p.variable("expr", BIGINT), p.variable("expr", BIGINT))
                                                                .put(p.variable("random", BIGINT), p.rowExpression("random()"))
                                                                .build(),
                                                        p.values(p.variable("user_uuid", VARCHAR), p.variable("expr", BIGINT)))))))
                .matches(
                        output(
                                project(
                                        project(
                                                values("user_uuid")))));
    }

    @Test
    public void testIntersectNodePruning()
    {
        assertRuleApplication()
                .on(p ->
                        p.output(ImmutableList.of("regionkey"), ImmutableList.of(p.variable("regionkey_16")),
                                p.project(Assignments.of(p.variable("regionkey_16"), p.variable("regionkey_16")),
                                        p.intersect(
                                                ImmutableListMultimap.<VariableReferenceExpression, VariableReferenceExpression>builder()
                                                        .putAll(p.variable("nationkey_15"), p.variable("nationkey"), p.variable("regionkey_6"))
                                                        .putAll(p.variable("regionkey_16"), p.variable("regionkey"), p.variable("regionkey_6"))
                                                        .build(),
                                                ImmutableList.of(
                                                        p.values(p.variable("nationkey"), p.variable("regionkey")),
                                                        p.values(p.variable("regionkey_6")))))))
                .matches(
                        output(
                                project(
                                        intersect(
                                                values("nationkey", "regionkey"),
                                                values("regionkey_6")))));
    }

    @Test
    public void testExceptNodePruning()
    {
        assertRuleApplication()
                .on(p ->
                        p.output(ImmutableList.of("regionkey"), ImmutableList.of(p.variable("regionkey_16")),
                                p.project(Assignments.of(p.variable("regionkey_16"), p.variable("regionkey_16")),
                                        p.except(
                                                ImmutableListMultimap.<VariableReferenceExpression, VariableReferenceExpression>builder()
                                                        .putAll(p.variable("nationkey_15"), p.variable("nationkey"), p.variable("regionkey_6"))
                                                        .putAll(p.variable("regionkey_16"), p.variable("regionkey"), p.variable("regionkey_6"))
                                                        .build(),
                                                ImmutableList.of(
                                                        p.values(p.variable("nationkey"), p.variable("regionkey")),
                                                        p.values(p.variable("regionkey_6")))))))
                .matches(
                        output(
                                project(
                                        except(
                                                values("nationkey", "regionkey"),
                                                values("regionkey_6")))));
    }

    @Test
    public void testIndexJoinNodePruning()
    {
        assertRuleApplication()
                .on(p ->
                        p.output(ImmutableList.of("totoalprice"), ImmutableList.of(p.variable("totoalprice")),
                                p.indexJoin(JoinType.LEFT,
                                        p.tableScan(
                                                new TableHandle(
                                                        new ConnectorId("local"),
                                                        new TpchTableHandle("lineitem", TINY_SCALE_FACTOR),
                                                        TestingTransactionHandle.create(),
                                                        Optional.empty()),
                                                ImmutableList.of(p.variable("partkey"), p.variable("suppkey")),
                                                ImmutableMap.of(
                                                        p.variable("partkey", BIGINT), new TpchColumnHandle("partkey", BIGINT),
                                                        p.variable("suppkey", BIGINT), new TpchColumnHandle("suppkey", BIGINT))),
                                        p.indexSource(
                                                new TableHandle(
                                                        new ConnectorId("local"),
                                                        new TpchTableHandle("orders", TINY_SCALE_FACTOR),
                                                        TestingTransactionHandle.create(),
                                                        Optional.empty()),
                                                ImmutableSet.of(p.variable("custkey"), p.variable("orderkey"), p.variable("orderstatus", VARCHAR)),
                                                ImmutableList.of(p.variable("custkey"), p.variable("orderkey"), p.variable("orderstatus", VARCHAR), p.variable("totalprice", DOUBLE)),
                                                ImmutableMap.of(
                                                        p.variable("custkey", BIGINT), new TpchColumnHandle("custkey", BIGINT),
                                                        p.variable("orderkey", BIGINT), new TpchColumnHandle("orderkey", BIGINT),
                                                        p.variable("totalprice", DOUBLE), new TpchColumnHandle("totalprice", DOUBLE),
                                                        p.variable("orderstatus", VARCHAR), new TpchColumnHandle("orderstatus", VARCHAR)),
                                                TupleDomain.all()),
                                        ImmutableList.of(new IndexJoinNode.EquiJoinClause(p.variable("partkey", BIGINT), p.variable("orderkey", BIGINT))),
                                        Optional.of(p.rowExpression("custkey BETWEEN suppkey AND 20")))))
                .matches(
                        output(
                                indexJoin(
                                        strictTableScan("lineitem", ImmutableMap.of("partkey", "partkey", "suppkey", "suppkey")),
                                        strictIndexSource("orders",
                                                ImmutableMap.of("custkey", "custkey", "orderkey", "orderkey", "orderstatus", "orderstatus", "totalprice", "totalprice")))));
    }

    private OptimizerAssert assertRuleApplication()
    {
        RuleTester tester = tester();
        return tester.assertThat(new PruneUnreferencedOutputs());
    }
}
