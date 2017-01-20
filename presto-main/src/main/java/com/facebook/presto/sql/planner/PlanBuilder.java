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
package com.facebook.presto.sql.planner;

import com.facebook.presto.sql.analyzer.Analysis;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.ExpressionTreeRewriter;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

class PlanBuilder
{
    private final TranslationMap translations;
    private final List<Expression> parameters;
    private final PlanNode root;

    public PlanBuilder(TranslationMap translations, PlanNode root, List<Expression> parameters)
    {
        requireNonNull(translations, "translations is null");
        requireNonNull(root, "root is null");
        requireNonNull(parameters, "parameterRewriter is null");

        this.translations = translations;
        this.root = root;
        this.parameters = parameters;
    }

    public TranslationMap copyTranslations()
    {
        TranslationMap translations = new TranslationMap(getRelationPlan(), getAnalysis(), getTranslations().getLambdaDeclarationToSymbolMap());
        translations.copyMappingsFrom(getTranslations());
        return translations;
    }

    private Analysis getAnalysis()
    {
        return translations.getAnalysis();
    }

    public PlanBuilder withNewRoot(PlanNode root)
    {
        return new PlanBuilder(translations, root, parameters);
    }

    public RelationPlan getRelationPlan()
    {
        return translations.getRelationPlan();
    }

    public PlanNode getRoot()
    {
        return root;
    }

    public boolean canTranslate(Expression expression)
    {
        return translations.containsSymbol(expression);
    }

    public Symbol translate(Expression expression)
    {
        return translations.get(expression);
    }

    public Expression rewrite(Expression expression)
    {
        return translations.rewrite(expression);
    }

    public TranslationMap getTranslations()
    {
        return translations;
    }

    public PlanBuilder prependProjections(Iterable<Expression> expressions, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator)
    {
        TranslationMap translations = copyTranslations();

        Assignments.Builder projections = Assignments.builder();

        // prepend the new translations into the TranslationMap
        Map<Symbol, Expression> newTranslations = buildProjectionMap(expressions, symbolAllocator, translations, projections);
        for (Map.Entry<Symbol, Expression> entry : newTranslations.entrySet()) {
            translations.put(entry.getValue(), entry.getKey());
        }

        // add an identity projection for underlying plan
        for (Symbol symbol : getRoot().getOutputSymbols()) {
            projections.put(symbol, symbol.toSymbolReference());
        }

        return new PlanBuilder(translations, new ProjectNode(idAllocator.getNextId(), getRoot(), projections.build()), parameters);
    }

    public PlanBuilder appendProjections(Iterable<Expression> expressions, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator)
    {
        TranslationMap translations = copyTranslations();

        Assignments.Builder projections = Assignments.builder();

        // add an identity projection for underlying plan
        for (Symbol symbol : getRoot().getOutputSymbols()) {
            projections.put(symbol, symbol.toSymbolReference());
        }

        // append the new translations into the TranslationMap
        Map<Symbol, Expression> newTranslations = buildProjectionMap(expressions, symbolAllocator, translations, projections);
        for (Map.Entry<Symbol, Expression> entry : newTranslations.entrySet()) {
            translations.put(entry.getValue(), entry.getKey());
        }

        return new PlanBuilder(translations, new ProjectNode(idAllocator.getNextId(), getRoot(), projections.build()), parameters);
    }

    private Map<Symbol, Expression> buildProjectionMap(
            Iterable<Expression> expressions,
            SymbolAllocator symbolAllocator,
            TranslationMap translations,
            Assignments.Builder projections)
    {
        ImmutableMap.Builder<Symbol, Expression> newTranslations = ImmutableMap.builder();
        ParameterRewriter parameterRewriter = new ParameterRewriter(parameters, getAnalysis());
        for (Expression expression : expressions) {
            Expression rewritten = ExpressionTreeRewriter.rewriteWith(parameterRewriter, expression);
            translations.addIntermediateMapping(expression, rewritten);
            Symbol symbol = symbolAllocator.newSymbol(rewritten, getAnalysis().getTypeWithCoercions(expression));
            projections.put(symbol, translations.rewrite(rewritten));
            newTranslations.put(symbol, rewritten);
        }
        return newTranslations.build();
    }
}
