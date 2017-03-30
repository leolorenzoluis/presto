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

package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.cost.PlanNodeCost;
import com.facebook.presto.sql.ExpressionUtils;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolReferenceExtractor;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.sql.planner.plan.JoinNode.DistributionType.PARTITIONED;
import static com.facebook.presto.util.ImmutableCollectors.toImmutableList;
import static com.facebook.presto.util.ImmutableCollectors.toImmutableSet;

public class ReorderJoinsUtils
{
    private ReorderJoinsUtils() {}

    // Todo: this is temporary until a method is added to cost calculator that returns a cumulative cost
    public static double getCost(PlanNodeCost planNodeCost)
    {
        return planNodeCost.getOutputRowCount().isValueUnknown() ? Double.POSITIVE_INFINITY : planNodeCost.getOutputRowCount().getValue();
    }

    public static Set<Set<Integer>> generatePartitions(int totalNodes)
    {
        return generatePartitions(ImmutableSet.of(0), totalNodes, 1);
    }

    private static Set<Set<Integer>> generatePartitions(Set<Integer> existingSet, int totalNodes, int index)
    {
        ImmutableSet.Builder<Set<Integer>> sets = ImmutableSet.builder();
        if (index < totalNodes && existingSet.size() < totalNodes - 1) {
            ImmutableSet.Builder<Integer> setWithAddedNode = ImmutableSet.builder();
            setWithAddedNode.addAll(existingSet);
            setWithAddedNode.add(index);
            sets.addAll(generatePartitions(setWithAddedNode.build(), totalNodes, index + 1));
            sets.addAll(generatePartitions(existingSet, totalNodes, index + 1));
        }
        else {
            sets.add(existingSet);
        }
        return sets.build();
    }

    public static JoinNode createBinaryJoin(JoinGraphNode joinGraph, Set<Integer> leftSources, PlanNodeIdAllocator idAllocator)
    {
        List<PlanNode> sources = joinGraph.getSources();
        ImmutableList.Builder<PlanNode> leftNodes = ImmutableList.builder();
        List<Symbol> leftSymbols = new ArrayList<>();
        ImmutableList.Builder<PlanNode> rightNodes = ImmutableList.builder();
        List<Symbol> rightSymbols = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            PlanNode source = sources.get(i);
            if (leftSources.contains(i)) {
                leftNodes.add(source);
                leftSymbols.addAll(source.getOutputSymbols());
            }
            else {
                rightNodes.add(source);
                rightSymbols.addAll(source.getOutputSymbols());
            }
        }

        ImmutableList.Builder<JoinNode.EquiJoinClause> leftCriteria = ImmutableList.builder();
        ImmutableList.Builder<JoinNode.EquiJoinClause> rightCriteria = ImmutableList.builder();
        ImmutableList.Builder<JoinNode.EquiJoinClause> spanningCriteria = ImmutableList.builder();
        for (JoinNode.EquiJoinClause equiJoinClause : joinGraph.getCriteria()) {
            Set<Symbol> equiJoinClauseSymbols = ImmutableSet.of(equiJoinClause.getLeft(), equiJoinClause.getRight());
            if (leftSymbols.containsAll(equiJoinClauseSymbols)) {
                leftCriteria.add(equiJoinClause);
            }
            else if (rightSymbols.containsAll(equiJoinClauseSymbols)) {
                rightCriteria.add(equiJoinClause);
            }
            else {
                spanningCriteria.add(equiJoinClause);
            }
        }

        ImmutableList.Builder<Expression> leftFilters = ImmutableList.builder();
        ImmutableList.Builder<Expression> rightFilters = ImmutableList.builder();
        ImmutableList.Builder<Expression> spanningFilters = ImmutableList.builder();
        for (Expression filter : joinGraph.getFilters()) {
            Set<Symbol> filterSymbols = SymbolReferenceExtractor.getSymbolReferences(filter).stream()
                    .map(Symbol::from)
                    .collect(toImmutableSet());
            if (leftSymbols.containsAll(filterSymbols)) {
                leftFilters.add(filter);
            }
            else if (rightSymbols.containsAll(filterSymbols)) {
                rightFilters.add(filter);
            }
            else {
                spanningFilters.add(filter);
            }
        }

        PlanNode left = new JoinGraphNode(idAllocator.getNextId(), leftNodes.build(), leftCriteria.build(), leftFilters.build());
        if (left.getSources().size() == 1) {
            left = ((JoinGraphNode) left).extractOnlySource(idAllocator);
        }
        PlanNode right = new JoinGraphNode(idAllocator.getNextId(), rightNodes.build(), rightCriteria.build(), rightFilters.build());
        if (right.getSources().size() == 1) {
            right = ((JoinGraphNode) right).extractOnlySource(idAllocator);
        }

        List<Expression> filters = spanningFilters.build();

        return new JoinNode(
                idAllocator.getNextId(),
                JoinNode.Type.INNER,
                left,
                right,
                flipJoinCriteriaIfNecessary(spanningCriteria.build(), left),
                ImmutableList.<Symbol>builder().addAll(left.getOutputSymbols()).addAll(right.getOutputSymbols()).build(),
                filters.isEmpty() ? Optional.empty() : Optional.of(ExpressionUtils.and(filters)),
                Optional.empty(),
                Optional.empty(),
                Optional.of(PARTITIONED));
    }

    private static List<JoinNode.EquiJoinClause> flipJoinCriteriaIfNecessary(List<JoinNode.EquiJoinClause> joinCriteria, PlanNode left)
    {
        return joinCriteria
                .stream()
                .map(x -> left.getOutputSymbols().contains(x.getLeft()) ? x : new JoinNode.EquiJoinClause(x.getRight(), x.getLeft()))
                .collect(toImmutableList());
    }
}
