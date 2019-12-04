/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
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

package com.palantir.baseline.errorprone;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.fixes.SuggestedFixes;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;

@AutoService(BugChecker.class)
@BugPattern(
        name = "ThrowSpecificity",
        link = "https://github.com/palantir/gradle-baseline#baseline-error-prone-checks",
        linkType = BugPattern.LinkType.CUSTOM,
        severity = BugPattern.SeverityLevel.SUGGESTION,
        summary = "Prefer to declare more specific throws types than Exception and Throwable. When methods are "
                + "updated to throw new checked exceptions they expect callers to handle failure types explicitly. "
                + "Throwing broad types defeats the type system. By throwing the most specific types possible we "
                + "leverage existing compiler functionality to detect unreachable code.")
public final class ThrowSpecificity extends BugChecker implements BugChecker.MethodTreeMatcher {

    // Maximum of three checked exception types to avoid unreadable long catch statements.
    private static final int MAX_CHECKED_EXCEPTIONS = 3;

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
        List<? extends ExpressionTree> throwsExpressions = tree.getThrows();
        if (throwsExpressions.size() != 1 || !safeToModifyThrowsClause(tree)) {
            return Description.NO_MATCH;
        }
        Types types = state.getTypes();
        if (ASTHelpers.findSuperMethod(ASTHelpers.getSymbol(tree), types).isPresent()) {
            return Description.NO_MATCH;
        }
        ExpressionTree throwsExpression = Iterables.getOnlyElement(throwsExpressions);
        Type throwsExpressionType = ASTHelpers.getType(throwsExpression);
        if (throwsExpressionType == null || !isBroadException(throwsExpressionType, state)) {
            return Description.NO_MATCH;
        }

        ImmutableSet<Type> allThrownExceptions = MoreASTHelpers.getThrownExceptions(tree.getBody(), state);
        ImmutableList<Type> normalizedThrownExceptions = allThrownExceptions.stream()
                .filter(type -> MoreASTHelpers.isCheckedException(type, state))
                .collect(ImmutableList.toImmutableList());
        ImmutableList<Type> checkedExceptions =
                MoreASTHelpers.flattenTypesForAssignment(normalizedThrownExceptions, state);
        if (checkedExceptions.size() > MAX_CHECKED_EXCEPTIONS
                || containsBroadException(checkedExceptions, state)
                // Avoid code churn in test sources for the time being.
                || TestCheckUtils.isTestCode(state)) {
            return Description.NO_MATCH;
        }

        if (checkedExceptions.isEmpty()) {
            return buildDescription(throwsExpression)
                    .addFix(SuggestedFixes.deleteExceptions(tree, state, ImmutableList.of(throwsExpression)))
                    .build();
        }
        SuggestedFix.Builder fix = SuggestedFix.builder();
        return buildDescription(throwsExpression)
                .addFix(fix.replace(throwsExpression, checkedExceptions.stream()
                        .map(checkedException -> SuggestedFixes.prettyType(state, fix, checkedException))
                        .collect(Collectors.joining(", ")))
                        .build())
                .build();
    }

    /** Avoid modifying methods which may me overridden and public API. */
    private static boolean safeToModifyThrowsClause(MethodTree tree) {
        Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(tree);
        if (symbol == null) {
            return false;
        }
        Set<Modifier> methodModifiers = symbol.getModifiers();
        if (symbol.isPrivate()) {
            return true;
        }
        return !methodModifiers.contains(Modifier.ABSTRACT)
                // Don't suggest modifying public API
                && !methodModifiers.contains(Modifier.PUBLIC)
                && (symbol.isStatic()
                || methodModifiers.contains(Modifier.FINAL)
                || ASTHelpers.enclosingClass(symbol).getModifiers().contains(Modifier.FINAL));
    }

    private static boolean containsBroadException(Collection<Type> exceptions, VisitorState state) {
        return exceptions.stream().anyMatch(type -> isBroadException(type, state));
    }

    private static boolean isBroadException(Type type, VisitorState state) {
        return ASTHelpers.isSameType(state.getTypeFromString(Exception.class.getName()), type, state)
                || ASTHelpers.isSameType(state.getTypeFromString(Throwable.class.getName()), type, state);
    }
}