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
package com.facebook.presto.sql.analyzer;

import com.facebook.presto.Session;
import com.facebook.presto.UnknownTypeException;
import com.facebook.presto.common.ErrorCode;
import com.facebook.presto.common.QualifiedObjectName;
import com.facebook.presto.common.Subfield;
import com.facebook.presto.common.function.OperatorType;
import com.facebook.presto.common.function.SqlFunctionProperties;
import com.facebook.presto.common.transaction.TransactionId;
import com.facebook.presto.common.type.CharType;
import com.facebook.presto.common.type.DecimalParseResult;
import com.facebook.presto.common.type.DecimalType;
import com.facebook.presto.common.type.Decimals;
import com.facebook.presto.common.type.DistinctType;
import com.facebook.presto.common.type.FunctionType;
import com.facebook.presto.common.type.MapType;
import com.facebook.presto.common.type.RowType;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.common.type.TypeSignatureParameter;
import com.facebook.presto.common.type.TypeUtils;
import com.facebook.presto.common.type.TypeWithName;
import com.facebook.presto.common.type.VarcharType;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.OperatorNotFoundException;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.PrestoWarning;
import com.facebook.presto.spi.StandardErrorCode;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.FunctionMetadata;
import com.facebook.presto.spi.function.SqlFunctionId;
import com.facebook.presto.spi.function.SqlInvokedFunction;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.spi.security.DenyAllAccessControl;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.relational.FunctionResolution;
import com.facebook.presto.sql.tree.ArithmeticBinaryExpression;
import com.facebook.presto.sql.tree.ArithmeticUnaryExpression;
import com.facebook.presto.sql.tree.ArrayConstructor;
import com.facebook.presto.sql.tree.AtTimeZone;
import com.facebook.presto.sql.tree.BetweenPredicate;
import com.facebook.presto.sql.tree.BinaryLiteral;
import com.facebook.presto.sql.tree.BindExpression;
import com.facebook.presto.sql.tree.BooleanLiteral;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.CharLiteral;
import com.facebook.presto.sql.tree.CoalesceExpression;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.CurrentTime;
import com.facebook.presto.sql.tree.CurrentUser;
import com.facebook.presto.sql.tree.DecimalLiteral;
import com.facebook.presto.sql.tree.DereferenceExpression;
import com.facebook.presto.sql.tree.DoubleLiteral;
import com.facebook.presto.sql.tree.EnumLiteral;
import com.facebook.presto.sql.tree.ExistsPredicate;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Extract;
import com.facebook.presto.sql.tree.FieldReference;
import com.facebook.presto.sql.tree.FrameBound;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.GenericLiteral;
import com.facebook.presto.sql.tree.GroupingOperation;
import com.facebook.presto.sql.tree.Identifier;
import com.facebook.presto.sql.tree.IfExpression;
import com.facebook.presto.sql.tree.InListExpression;
import com.facebook.presto.sql.tree.InPredicate;
import com.facebook.presto.sql.tree.IntervalLiteral;
import com.facebook.presto.sql.tree.IsNotNullPredicate;
import com.facebook.presto.sql.tree.IsNullPredicate;
import com.facebook.presto.sql.tree.LambdaArgumentDeclaration;
import com.facebook.presto.sql.tree.LambdaExpression;
import com.facebook.presto.sql.tree.LikePredicate;
import com.facebook.presto.sql.tree.LogicalBinaryExpression;
import com.facebook.presto.sql.tree.LongLiteral;
import com.facebook.presto.sql.tree.Node;
import com.facebook.presto.sql.tree.NodeRef;
import com.facebook.presto.sql.tree.NotExpression;
import com.facebook.presto.sql.tree.NullIfExpression;
import com.facebook.presto.sql.tree.NullLiteral;
import com.facebook.presto.sql.tree.OrderBy;
import com.facebook.presto.sql.tree.Parameter;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.QuantifiedComparisonExpression;
import com.facebook.presto.sql.tree.Row;
import com.facebook.presto.sql.tree.SearchedCaseExpression;
import com.facebook.presto.sql.tree.SimpleCaseExpression;
import com.facebook.presto.sql.tree.SortItem;
import com.facebook.presto.sql.tree.StackableAstVisitor;
import com.facebook.presto.sql.tree.StringLiteral;
import com.facebook.presto.sql.tree.SubqueryExpression;
import com.facebook.presto.sql.tree.SubscriptExpression;
import com.facebook.presto.sql.tree.SymbolReference;
import com.facebook.presto.sql.tree.TimeLiteral;
import com.facebook.presto.sql.tree.TimestampLiteral;
import com.facebook.presto.sql.tree.TryExpression;
import com.facebook.presto.sql.tree.WhenClause;
import com.facebook.presto.sql.tree.Window;
import com.facebook.presto.sql.tree.WindowFrame;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import io.airlift.slice.SliceUtf8;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;

import static com.facebook.presto.common.function.OperatorType.ADD;
import static com.facebook.presto.common.function.OperatorType.SUBSCRIPT;
import static com.facebook.presto.common.function.OperatorType.SUBTRACT;
import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.BooleanType.BOOLEAN;
import static com.facebook.presto.common.type.DateType.DATE;
import static com.facebook.presto.common.type.DoubleType.DOUBLE;
import static com.facebook.presto.common.type.IntegerType.INTEGER;
import static com.facebook.presto.common.type.JsonType.JSON;
import static com.facebook.presto.common.type.RealType.REAL;
import static com.facebook.presto.common.type.SmallintType.SMALLINT;
import static com.facebook.presto.common.type.TimeType.TIME;
import static com.facebook.presto.common.type.TimeWithTimeZoneType.TIME_WITH_TIME_ZONE;
import static com.facebook.presto.common.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.common.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static com.facebook.presto.common.type.TinyintType.TINYINT;
import static com.facebook.presto.common.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.common.type.UnknownType.UNKNOWN;
import static com.facebook.presto.common.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.common.type.VarcharType.VARCHAR;
import static com.facebook.presto.metadata.BuiltInTypeAndFunctionNamespaceManager.JAVA_BUILTIN_NAMESPACE;
import static com.facebook.presto.spi.StandardErrorCode.OPERATOR_NOT_FOUND;
import static com.facebook.presto.spi.StandardWarningCode.SEMANTIC_WARNING;
import static com.facebook.presto.sql.NodeUtils.getSortItemsFromOrderBy;
import static com.facebook.presto.sql.analyzer.Analyzer.verifyNoAggregateWindowOrGroupingFunctions;
import static com.facebook.presto.sql.analyzer.Analyzer.verifyNoExternalFunctions;
import static com.facebook.presto.sql.analyzer.ExpressionTreeUtils.isConstant;
import static com.facebook.presto.sql.analyzer.ExpressionTreeUtils.isNonNullConstant;
import static com.facebook.presto.sql.analyzer.ExpressionTreeUtils.tryResolveEnumLiteralType;
import static com.facebook.presto.sql.analyzer.FunctionArgumentCheckerForAccessControlUtils.getResolvedLambdaArguments;
import static com.facebook.presto.sql.analyzer.FunctionArgumentCheckerForAccessControlUtils.isTopMostReference;
import static com.facebook.presto.sql.analyzer.FunctionArgumentCheckerForAccessControlUtils.isUnusedArgumentForAccessControl;
import static com.facebook.presto.sql.analyzer.FunctionArgumentCheckerForAccessControlUtils.resolveSubfield;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.EXPRESSION_NOT_CONSTANT;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.INVALID_LITERAL;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.INVALID_ORDER_BY;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.INVALID_PARAMETER_USAGE;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.INVALID_PROCEDURE_ARGUMENTS;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.MISSING_ORDER_BY;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.MULTIPLE_FIELDS_FROM_SUBQUERY;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.STANDALONE_LAMBDA;
import static com.facebook.presto.sql.analyzer.SemanticErrorCode.TYPE_MISMATCH;
import static com.facebook.presto.sql.analyzer.SemanticExceptions.missingAttributeException;
import static com.facebook.presto.sql.analyzer.TypeSignatureProvider.fromTypes;
import static com.facebook.presto.sql.tree.Extract.Field.TIMEZONE_HOUR;
import static com.facebook.presto.sql.tree.Extract.Field.TIMEZONE_MINUTE;
import static com.facebook.presto.sql.tree.FrameBound.Type.FOLLOWING;
import static com.facebook.presto.sql.tree.FrameBound.Type.PRECEDING;
import static com.facebook.presto.sql.tree.SortItem.Ordering.ASCENDING;
import static com.facebook.presto.sql.tree.SortItem.Ordering.DESCENDING;
import static com.facebook.presto.sql.tree.WindowFrame.Type.GROUPS;
import static com.facebook.presto.sql.tree.WindowFrame.Type.RANGE;
import static com.facebook.presto.sql.tree.WindowFrame.Type.ROWS;
import static com.facebook.presto.type.ArrayParametricType.ARRAY;
import static com.facebook.presto.type.IntervalDayTimeType.INTERVAL_DAY_TIME;
import static com.facebook.presto.type.IntervalYearMonthType.INTERVAL_YEAR_MONTH;
import static com.facebook.presto.util.DateTimeUtils.parseTimestampLiteral;
import static com.facebook.presto.util.DateTimeUtils.timeHasTimeZone;
import static com.facebook.presto.util.DateTimeUtils.timestampHasTimeZone;
import static com.facebook.presto.util.LegacyRowFieldOrdinalAccessUtil.parseAnonymousRowFieldOrdinalAccess;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.Objects.requireNonNull;

public class ExpressionAnalyzer
{
    private static final int MAX_NUMBER_GROUPING_ARGUMENTS_BIGINT = 63;
    private static final int MAX_NUMBER_GROUPING_ARGUMENTS_INTEGER = 31;

    private final FunctionAndTypeResolver functionAndTypeResolver;
    private final FunctionResolution functionResolution;
    private final Function<Node, StatementAnalyzer> statementAnalyzerFactory;
    private final TypeProvider symbolTypes;
    private final boolean isDescribe;

    private final Map<NodeRef<FunctionCall>, FunctionHandle> resolvedFunctions = new LinkedHashMap<>();
    private final Set<NodeRef<SubqueryExpression>> scalarSubqueries = new LinkedHashSet<>();
    private final Set<NodeRef<ExistsPredicate>> existsSubqueries = new LinkedHashSet<>();
    private final Map<NodeRef<Expression>, Type> expressionCoercions = new LinkedHashMap<>();
    private final Set<NodeRef<Expression>> typeOnlyCoercions = new LinkedHashSet<>();

    // Coercions needed for window function frame of type RANGE.
    // These are coercions for the sort key, needed for frame bound calculation, identified by frame range offset expression.
    // Frame definition might contain two different offset expressions (for start and end), each requiring different coercion of the sort key.
    private final Map<NodeRef<Expression>, Type> sortKeyCoercionsForFrameBoundCalculation = new LinkedHashMap<>();
    // Coercions needed for window function frame of type RANGE.
    // These are coercions for the sort key, needed for comparison of the sort key with precomputed frame bound, identified by frame range offset expression.
    private final Map<NodeRef<Expression>, Type> sortKeyCoercionsForFrameBoundComparison = new LinkedHashMap<>();
    // Functions for calculating frame bounds for frame of type RANGE, identified by frame range offset expression.
    private final Map<NodeRef<Expression>, FunctionHandle> frameBoundCalculations = new LinkedHashMap<>();

    private final Set<NodeRef<InPredicate>> subqueryInPredicates = new LinkedHashSet<>();
    private final Map<NodeRef<Expression>, FieldId> columnReferences = new LinkedHashMap<>();
    private final Map<NodeRef<Expression>, Type> expressionTypes = new LinkedHashMap<>();
    private final Set<NodeRef<QuantifiedComparisonExpression>> quantifiedComparisons = new LinkedHashSet<>();
    // For lambda argument references, maps each QualifiedNameReference to the referenced LambdaArgumentDeclaration
    private final Map<NodeRef<Identifier>, LambdaArgumentDeclaration> lambdaArgumentReferences = new LinkedHashMap<>();
    private final Set<NodeRef<FunctionCall>> windowFunctions = new LinkedHashSet<>();
    private final Multimap<QualifiedObjectName, Subfield> tableColumnAndSubfieldReferences = HashMultimap.create();
    private final Multimap<QualifiedObjectName, Subfield> tableColumnAndSubfieldReferencesForAccessControl = HashMultimap.create();

    private final Optional<TransactionId> transactionId;
    private final Optional<Map<SqlFunctionId, SqlInvokedFunction>> sessionFunctions;
    private final SqlFunctionProperties sqlFunctionProperties;
    private final Map<NodeRef<Parameter>, Expression> parameters;
    private final WarningCollector warningCollector;
    // Map to resolved type of any symbols that ExpressionAnalyzer cannot resolved within current scope.
    // This contains types of variables referenced from outer scopes.
    private final Map<NodeRef<Expression>, Type> outerScopeSymbolTypes;

    private ExpressionAnalyzer(
            FunctionAndTypeResolver functionAndTypeResolver,
            Function<Node, StatementAnalyzer> statementAnalyzerFactory,
            Optional<Map<SqlFunctionId, SqlInvokedFunction>> sessionFunctions,
            Optional<TransactionId> transactionId,
            SqlFunctionProperties sqlFunctionProperties,
            TypeProvider symbolTypes,
            Map<NodeRef<Parameter>, Expression> parameters,
            WarningCollector warningCollector,
            boolean isDescribe,
            Map<NodeRef<Expression>, Type> outerScopeSymbolTypes)
    {
        this.functionAndTypeResolver = requireNonNull(functionAndTypeResolver, "functionAndTypeResolver is null");
        this.functionResolution = new FunctionResolution(functionAndTypeResolver);
        this.statementAnalyzerFactory = requireNonNull(statementAnalyzerFactory, "statementAnalyzerFactory is null");
        this.transactionId = requireNonNull(transactionId, "transactionId is null");
        this.sessionFunctions = requireNonNull(sessionFunctions, "sessionFunctions is null");
        this.sqlFunctionProperties = requireNonNull(sqlFunctionProperties, "sqlFunctionProperties is null");
        this.symbolTypes = requireNonNull(symbolTypes, "symbolTypes is null");
        this.parameters = requireNonNull(parameters, "parameterMap is null");
        this.isDescribe = isDescribe;
        this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
        this.outerScopeSymbolTypes = requireNonNull(outerScopeSymbolTypes, "outerScopeSymbolTypes is null");
    }

    public Map<NodeRef<FunctionCall>, FunctionHandle> getResolvedFunctions()
    {
        return unmodifiableMap(resolvedFunctions);
    }

    public Map<NodeRef<Expression>, Type> getExpressionTypes()
    {
        return unmodifiableMap(expressionTypes);
    }

    public Type setExpressionType(Expression expression, Type type)
    {
        requireNonNull(expression, "expression cannot be null");
        requireNonNull(type, "type cannot be null");

        expressionTypes.put(NodeRef.of(expression), type);

        return type;
    }

    private Type getExpressionType(Expression expression)
    {
        requireNonNull(expression, "expression cannot be null");

        Type type = expressionTypes.get(NodeRef.of(expression));
        checkState(type != null, "Expression not yet analyzed: %s", expression);
        return type;
    }

    public Map<NodeRef<Expression>, Type> getExpressionCoercions()
    {
        return unmodifiableMap(expressionCoercions);
    }

    public Set<NodeRef<Expression>> getTypeOnlyCoercions()
    {
        return unmodifiableSet(typeOnlyCoercions);
    }

    public Map<NodeRef<Expression>, Type> getSortKeyCoercionsForFrameBoundCalculation()
    {
        return unmodifiableMap(sortKeyCoercionsForFrameBoundCalculation);
    }

    public Map<NodeRef<Expression>, Type> getSortKeyCoercionsForFrameBoundComparison()
    {
        return unmodifiableMap(sortKeyCoercionsForFrameBoundComparison);
    }

    public Map<NodeRef<Expression>, FunctionHandle> getFrameBoundCalculations()
    {
        return unmodifiableMap(frameBoundCalculations);
    }

    public Set<NodeRef<InPredicate>> getSubqueryInPredicates()
    {
        return unmodifiableSet(subqueryInPredicates);
    }

    public Map<NodeRef<Expression>, FieldId> getColumnReferences()
    {
        return unmodifiableMap(columnReferences);
    }

    public Map<NodeRef<Identifier>, LambdaArgumentDeclaration> getLambdaArgumentReferences()
    {
        return unmodifiableMap(lambdaArgumentReferences);
    }

    public Type analyze(Expression expression, Scope scope)
    {
        Visitor visitor = new Visitor(scope, warningCollector);
        return visitor.process(expression, new StackableAstVisitor.StackableAstVisitorContext<>(Context.notInLambda(scope)));
    }

    private Type analyze(Expression expression, Scope baseScope, Context context)
    {
        Visitor visitor = new Visitor(baseScope, warningCollector);
        return visitor.process(expression, new StackableAstVisitor.StackableAstVisitorContext<>(context));
    }

    public Set<NodeRef<SubqueryExpression>> getScalarSubqueries()
    {
        return unmodifiableSet(scalarSubqueries);
    }

    public Set<NodeRef<ExistsPredicate>> getExistsSubqueries()
    {
        return unmodifiableSet(existsSubqueries);
    }

    public Set<NodeRef<QuantifiedComparisonExpression>> getQuantifiedComparisons()
    {
        return unmodifiableSet(quantifiedComparisons);
    }

    public Set<NodeRef<FunctionCall>> getWindowFunctions()
    {
        return unmodifiableSet(windowFunctions);
    }

    public Multimap<QualifiedObjectName, Subfield> getTableColumnAndSubfieldReferences()
    {
        return tableColumnAndSubfieldReferences;
    }

    public Multimap<QualifiedObjectName, Subfield> getTableColumnAndSubfieldReferencesForAccessControl()
    {
        return tableColumnAndSubfieldReferencesForAccessControl;
    }

    private class Visitor
            extends StackableAstVisitor<Type, Context>
    {
        // Used to resolve FieldReferences (e.g. during local execution planning)
        private final Scope baseScope;
        private final WarningCollector warningCollector;

        public Visitor(Scope baseScope, WarningCollector warningCollector)
        {
            this.baseScope = requireNonNull(baseScope, "baseScope is null");
            this.warningCollector = requireNonNull(warningCollector, "warningCollector is null");
        }

        @Override
        public Type process(Node node, @Nullable StackableAstVisitorContext<Context> context)
        {
            if (node instanceof Expression) {
                // don't double process a node
                Type type = expressionTypes.get(NodeRef.of(((Expression) node)));
                if (type != null) {
                    return type;
                }
            }
            return super.process(node, context);
        }

        @Override
        protected Type visitRow(Row node, StackableAstVisitorContext<Context> context)
        {
            List<Type> types = node.getItems().stream()
                    .map((child) -> process(child, context))
                    .collect(toImmutableList());

            Type type = RowType.anonymous(types);
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitCurrentTime(CurrentTime node, StackableAstVisitorContext<Context> context)
        {
            if (node.getPrecision() != null) {
                throw new SemanticException(NOT_SUPPORTED, node, "non-default precision not yet supported");
            }

            Type type;
            switch (node.getFunction()) {
                case DATE:
                    type = DATE;
                    break;
                case TIME:
                    type = TIME_WITH_TIME_ZONE;
                    break;
                case LOCALTIME:
                    type = TIME;
                    break;
                case TIMESTAMP:
                    type = TIMESTAMP_WITH_TIME_ZONE;
                    break;
                case LOCALTIMESTAMP:
                    type = TIMESTAMP;
                    break;
                default:
                    throw new SemanticException(NOT_SUPPORTED, node, "%s not yet supported", node.getFunction().getName());
            }

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitSymbolReference(SymbolReference node, StackableAstVisitorContext<Context> context)
        {
            if (context.getContext().isInLambda()) {
                Optional<ResolvedField> resolvedField = context.getContext().getScope().tryResolveField(node, QualifiedName.of(node.getName()));
                if (resolvedField.isPresent() && context.getContext().getFieldToLambdaArgumentDeclaration().containsKey(FieldId.from(resolvedField.get()))) {
                    return setExpressionType(node, resolvedField.get().getType());
                }
            }
            Type type = symbolTypes.get(node);
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitIdentifier(Identifier node, StackableAstVisitorContext<Context> context)
        {
            QualifiedName name = QualifiedName.of(ImmutableList.of(new Identifier(node.getValue(), node.isDelimited())));
            Optional<ResolvedField> resolvedField = context.getContext().getScope().tryResolveField(node, name);
            if (!resolvedField.isPresent() && outerScopeSymbolTypes.containsKey(NodeRef.of(node))) {
                return setExpressionType(node, outerScopeSymbolTypes.get(NodeRef.of(node)));
            }
            return handleResolvedField(node, resolvedField.orElseThrow(() -> missingAttributeException(node, name)), context);
        }

        private Type handleResolvedField(Expression node, ResolvedField resolvedField, StackableAstVisitorContext<Context> context)
        {
            return handleResolvedField(node, FieldId.from(resolvedField), resolvedField.getField(), context);
        }

        private Type handleResolvedField(Expression node, FieldId fieldId, Field field, StackableAstVisitorContext<Context> context)
        {
            if (context.getContext().isInLambda()) {
                LambdaArgumentDeclaration lambdaArgumentDeclaration = context.getContext().getFieldToLambdaArgumentDeclaration().get(fieldId);
                if (lambdaArgumentDeclaration != null) {
                    // Lambda argument reference is not a column reference
                    lambdaArgumentReferences.put(NodeRef.of((Identifier) node), lambdaArgumentDeclaration);
                    if (!context.getContext().getResolvedLambdaArguments().containsKey(node)) {
                        return setExpressionType(node, field.getType());
                    }
                }
            }

            // If we found a direct column reference, and we will put it in tableColumnReferencesWithSubFields
            if (isTopMostReference(node, context)) {
                Optional<QualifiedObjectName> tableName = field.getOriginTable();
                Optional<Subfield> subfield = field.getOriginColumnName().map(column -> new Subfield(column, ImmutableList.of()));
                ResolvedSubfield resolvedSubfield = context.getContext().getResolvedLambdaArguments().get(node);
                if (resolvedSubfield != null) {
                    tableName = resolvedSubfield.getResolvedField().getField().getOriginTable();
                    subfield = Optional.of(resolvedSubfield.getSubfield());
                }
                if (tableName.isPresent() && subfield.isPresent()) {
                    tableColumnAndSubfieldReferences.put(tableName.get(), subfield.get());
                    if (!context.getContext().getUnusedExpressionsForAccessControl().contains(NodeRef.of(node))) {
                        tableColumnAndSubfieldReferencesForAccessControl.put(tableName.get(), subfield.get());
                    }
                }
            }

            FieldId previous = columnReferences.put(NodeRef.of(node), fieldId);
            checkState(previous == null, "%s already known to refer to %s", node, previous);
            return setExpressionType(node, field.getType());
        }

        @Override
        protected Type visitDereferenceExpression(DereferenceExpression node, StackableAstVisitorContext<Context> context)
        {
            QualifiedName qualifiedName = DereferenceExpression.getQualifiedName(node);

            // Handle qualified name
            if (qualifiedName != null) {
                // first, try to match it to a column name
                Scope scope = context.getContext().getScope();
                Optional<ResolvedField> resolvedField = scope.tryResolveField(node, qualifiedName);
                if (resolvedField.isPresent()) {
                    return handleResolvedField(node, resolvedField.get(), context);
                }
                // otherwise, try to match it to an enum literal (eg Mood.HAPPY)
                if (!scope.isColumnReference(qualifiedName)) {
                    Optional<TypeWithName> enumType = tryResolveEnumLiteralType(qualifiedName, functionAndTypeResolver);
                    if (enumType.isPresent()) {
                        setExpressionType(node.getBase(), enumType.get());
                        return setExpressionType(node, enumType.get());
                    }
                    if (outerScopeSymbolTypes.containsKey(NodeRef.of(node))) {
                        return setExpressionType(node, outerScopeSymbolTypes.get(NodeRef.of(node)));
                    }
                    throw missingAttributeException(node, qualifiedName);
                }
            }

            Type baseType = process(node.getBase(), context);
            addColumnSubfieldReferences(node, context);

            if (((baseType instanceof TypeWithName) && ((TypeWithName) baseType).getType() instanceof RowType)) {
                baseType = ((TypeWithName) baseType).getType();
            }
            if (baseType instanceof DistinctType) {
                baseType = ((DistinctType) baseType).getBaseType();
            }
            if (!(baseType instanceof RowType)) {
                throw new SemanticException(TYPE_MISMATCH, node.getBase(), "Expression %s is not of type ROW", node.getBase());
            }

            RowType rowType = (RowType) baseType;
            String fieldName = node.getField().getValue();

            Type rowFieldType = null;
            for (RowType.Field rowField : rowType.getFields()) {
                if (fieldName.equalsIgnoreCase(rowField.getName().orElse(null))) {
                    rowFieldType = rowField.getType();
                    break;
                }
            }

            if (sqlFunctionProperties.isLegacyRowFieldOrdinalAccessEnabled() && rowFieldType == null) {
                OptionalInt rowIndex = parseAnonymousRowFieldOrdinalAccess(fieldName, rowType.getFields());
                if (rowIndex.isPresent()) {
                    rowFieldType = rowType.getFields().get(rowIndex.getAsInt()).getType();
                }
            }

            if (rowFieldType == null) {
                qualifiedName = qualifiedName == null ? QualifiedName.of(node.toString()) : qualifiedName;
                throw missingAttributeException(node, qualifiedName);
            }

            return setExpressionType(node, rowFieldType);
        }

        @Override
        protected Type visitNotExpression(NotExpression node, StackableAstVisitorContext<Context> context)
        {
            coerceType(context, node.getValue(), BOOLEAN, "Value of logical NOT expression");

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitLogicalBinaryExpression(LogicalBinaryExpression node, StackableAstVisitorContext<Context> context)
        {
            coerceType(context, node.getLeft(), BOOLEAN, "Left side of logical expression");
            coerceType(context, node.getRight(), BOOLEAN, "Right side of logical expression");

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitComparisonExpression(ComparisonExpression node, StackableAstVisitorContext<Context> context)
        {
            OperatorType operatorType = OperatorType.valueOf(node.getOperator().name());
            Type outputType = getOperator(context, node, operatorType, node.getLeft(), node.getRight());
            // this needs to be checked after the call to getOperator(), because that's where the argument types get analyzed
            if (sqlFunctionProperties.shouldWarnOnCommonNanPatterns() &&
                    (TypeUtils.isApproximateNumericType(getExpressionType(node.getLeft())) || TypeUtils.isApproximateNumericType(getExpressionType(node.getRight())))) {
                warningCollector.add(new PrestoWarning(
                        SEMANTIC_WARNING,
                        "Comparison operations involving DOUBLE or REAL types may include NaNs in the input. " +
                                "Consider filtering out NaN values from your comparison input using the is_nan() function."));
            }
            return outputType;
        }

        @Override
        protected Type visitIsNullPredicate(IsNullPredicate node, StackableAstVisitorContext<Context> context)
        {
            Context newContext = context.getContext().withUnusedExpressionsForAccessControl(ImmutableSet.of(NodeRef.of(node.getValue())));
            process(node.getValue(), new StackableAstVisitorContext<>(newContext));

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitIsNotNullPredicate(IsNotNullPredicate node, StackableAstVisitorContext<Context> context)
        {
            Context newContext = context.getContext().withUnusedExpressionsForAccessControl(ImmutableSet.of(NodeRef.of(node.getValue())));
            process(node.getValue(), new StackableAstVisitorContext<>(newContext));

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitNullIfExpression(NullIfExpression node, StackableAstVisitorContext<Context> context)
        {
            Type firstType = process(node.getFirst(), context);
            Type secondType = process(node.getSecond(), context);

            if (!functionAndTypeResolver.getCommonSuperType(firstType, secondType).isPresent()) {
                throw new SemanticException(TYPE_MISMATCH, node, "Types are not comparable with NULLIF: %s vs %s", firstType, secondType);
            }

            return setExpressionType(node, firstType);
        }

        @Override
        protected Type visitIfExpression(IfExpression node, StackableAstVisitorContext<Context> context)
        {
            coerceType(context, node.getCondition(), BOOLEAN, "IF condition");

            Type type;
            if (node.getFalseValue().isPresent()) {
                type = coerceToSingleType(context, node, "Result types for IF must be the same: %s vs %s", node.getTrueValue(), node.getFalseValue().get());
            }
            else {
                type = process(node.getTrueValue(), context);
            }

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitSearchedCaseExpression(SearchedCaseExpression node, StackableAstVisitorContext<Context> context)
        {
            for (WhenClause whenClause : node.getWhenClauses()) {
                coerceType(context, whenClause.getOperand(), BOOLEAN, "CASE WHEN clause");
            }

            Type type = coerceToSingleType(context,
                    "All CASE results must be the same type: %s",
                    getCaseResultExpressions(node.getWhenClauses(), node.getDefaultValue()));
            setExpressionType(node, type);

            for (WhenClause whenClause : node.getWhenClauses()) {
                Type whenClauseType = process(whenClause.getResult(), context);
                setExpressionType(whenClause, whenClauseType);
            }

            return type;
        }

        @Override
        protected Type visitSimpleCaseExpression(SimpleCaseExpression node, StackableAstVisitorContext<Context> context)
        {
            for (WhenClause whenClause : node.getWhenClauses()) {
                coerceToSingleType(context, whenClause, "CASE operand type does not match WHEN clause operand type: %s vs %s", node.getOperand(), whenClause.getOperand());
            }

            Type type = coerceToSingleType(context,
                    "All CASE results must be the same type: %s",
                    getCaseResultExpressions(node.getWhenClauses(), node.getDefaultValue()));
            setExpressionType(node, type);

            for (WhenClause whenClause : node.getWhenClauses()) {
                Type whenClauseType = process(whenClause.getResult(), context);
                setExpressionType(whenClause, whenClauseType);
            }

            return type;
        }

        private List<Expression> getCaseResultExpressions(List<WhenClause> whenClauses, Optional<Expression> defaultValue)
        {
            List<Expression> resultExpressions = new ArrayList<>();
            for (WhenClause whenClause : whenClauses) {
                resultExpressions.add(whenClause.getResult());
            }
            defaultValue.ifPresent(resultExpressions::add);
            return resultExpressions;
        }

        @Override
        protected Type visitCoalesceExpression(CoalesceExpression node, StackableAstVisitorContext<Context> context)
        {
            Type type = coerceToSingleType(context, "All COALESCE operands must be the same type: %s", node.getOperands());

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitArithmeticUnary(ArithmeticUnaryExpression node, StackableAstVisitorContext<Context> context)
        {
            switch (node.getSign()) {
                case PLUS:
                    Type type = process(node.getValue(), context);

                    if (!type.equals(DOUBLE) && !type.equals(REAL) && !type.equals(BIGINT) && !type.equals(INTEGER) && !type.equals(SMALLINT) && !type.equals(TINYINT)) {
                        // TODO: figure out a type-agnostic way of dealing with this. Maybe add a special unary operator
                        // that types can chose to implement, or piggyback on the existence of the negation operator
                        throw new SemanticException(TYPE_MISMATCH, node, "Unary '+' operator cannot by applied to %s type", type);
                    }
                    return setExpressionType(node, type);
                case MINUS:
                    return getOperator(context, node, OperatorType.NEGATION, node.getValue());
            }

            throw new UnsupportedOperationException("Unsupported unary operator: " + node.getSign());
        }

        @Override
        protected Type visitArithmeticBinary(ArithmeticBinaryExpression node, StackableAstVisitorContext<Context> context)
        {
            Type returnType = getOperator(context, node, OperatorType.valueOf(node.getOperator().name()), node.getLeft(), node.getRight());
            if (sqlFunctionProperties.shouldWarnOnCommonNanPatterns() &&
                    node.getOperator() == ArithmeticBinaryExpression.Operator.DIVIDE &&
                    TypeUtils.isApproximateNumericType(returnType) &&
                    !isConstant(node.getLeft()) &&
                    !isConstant(node.getRight())) {
                warningCollector.add(new PrestoWarning(SEMANTIC_WARNING,
                        "Division operations on DOUBLE/REAL types may produce NaNs or infinities if there are zeros in the denominator. " +
                                "Consider checking the denominator of your division operation for zeros."));
            }
            return returnType;
        }

        @Override
        protected Type visitLikePredicate(LikePredicate node, StackableAstVisitorContext<Context> context)
        {
            Type valueType = process(node.getValue(), context);
            if (!(valueType instanceof CharType) && !(valueType instanceof VarcharType)) {
                coerceType(context, node.getValue(), VARCHAR, "Left side of LIKE expression");
            }

            Type patternType = getVarcharType(node.getPattern(), context);
            coerceType(context, node.getPattern(), patternType, "Pattern for LIKE expression");
            if (node.getEscape().isPresent()) {
                Expression escape = node.getEscape().get();
                Type escapeType = getVarcharType(escape, context);
                coerceType(context, escape, escapeType, "Escape for LIKE expression");
            }

            return setExpressionType(node, BOOLEAN);
        }

        private Type getVarcharType(Expression value, StackableAstVisitorContext<Context> context)
        {
            Type type = process(value, context);
            if (!(type instanceof VarcharType)) {
                return VARCHAR;
            }
            return type;
        }

        @Override
        protected Type visitSubscriptExpression(SubscriptExpression node, StackableAstVisitorContext<Context> context)
        {
            Type baseType = process(node.getBase(), context);
            // Subscript on Row hasn't got a dedicated operator. Its Type is resolved by hand.
            if (baseType instanceof RowType) {
                if (!(node.getIndex() instanceof LongLiteral)) {
                    throw new SemanticException(
                            INVALID_PARAMETER_USAGE,
                            node.getIndex(),
                            "Subscript expression on ROW requires a constant index");
                }
                Type indexType = process(node.getIndex(), context);
                if (!indexType.equals(INTEGER)) {
                    throw new SemanticException(
                            TYPE_MISMATCH,
                            node.getIndex(),
                            "Subscript expression on ROW requires integer index, found %s", indexType);
                }
                int indexValue = toIntExact(((LongLiteral) node.getIndex()).getValue());
                if (indexValue <= 0) {
                    throw new SemanticException(
                            INVALID_PARAMETER_USAGE,
                            node.getIndex(),
                            "Invalid subscript index: %s. ROW indices start at 1", indexValue);
                }
                List<Type> rowTypes = baseType.getTypeParameters();
                if (indexValue > rowTypes.size()) {
                    throw new SemanticException(
                            INVALID_PARAMETER_USAGE,
                            node.getIndex(),
                            "Subscript index out of bounds: %s, max value is %s", indexValue, rowTypes.size());
                }
                addColumnSubfieldReferences(node, context);
                return setExpressionType(node, rowTypes.get(indexValue - 1));
            }
            addColumnSubfieldReferences(node, context);
            return getOperator(context, node, SUBSCRIPT, node.getBase(), node.getIndex());
        }

        @Override
        protected Type visitArrayConstructor(ArrayConstructor node, StackableAstVisitorContext<Context> context)
        {
            Type type = coerceToSingleType(context, "All ARRAY elements must be the same type: %s", node.getValues());
            Type arrayType = functionAndTypeResolver.getParameterizedType(ARRAY.getName(), ImmutableList.of(TypeSignatureParameter.of(type.getTypeSignature())));
            return setExpressionType(node, arrayType);
        }

        @Override
        protected Type visitStringLiteral(StringLiteral node, StackableAstVisitorContext<Context> context)
        {
            VarcharType type = VarcharType.createVarcharType(SliceUtf8.countCodePoints(node.getSlice()));
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitCharLiteral(CharLiteral node, StackableAstVisitorContext<Context> context)
        {
            CharType type = CharType.createCharType(node.getValue().length());
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitBinaryLiteral(BinaryLiteral node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, VARBINARY);
        }

        @Override
        protected Type visitLongLiteral(LongLiteral node, StackableAstVisitorContext<Context> context)
        {
            if (node.getValue() >= Integer.MIN_VALUE && node.getValue() <= Integer.MAX_VALUE) {
                return setExpressionType(node, INTEGER);
            }

            return setExpressionType(node, BIGINT);
        }

        @Override
        protected Type visitDoubleLiteral(DoubleLiteral node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, functionAndTypeResolver.getType(DOUBLE.getTypeSignature()));
        }

        @Override
        protected Type visitDecimalLiteral(DecimalLiteral node, StackableAstVisitorContext<Context> context)
        {
            DecimalParseResult parseResult = Decimals.parse(node.getValue());
            return setExpressionType(node, parseResult.getType());
        }

        @Override
        protected Type visitBooleanLiteral(BooleanLiteral node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitGenericLiteral(GenericLiteral node, StackableAstVisitorContext<Context> context)
        {
            Type type;
            try {
                type = functionAndTypeResolver.getType(parseTypeSignature(node.getType()));
            }
            catch (IllegalArgumentException | UnknownTypeException e) {
                throw new SemanticException(TYPE_MISMATCH, node, "Unknown type: " + node.getType());
            }

            if (!JSON.equals(type)) {
                try {
                    functionAndTypeResolver.lookupCast("CAST", VARCHAR, type);
                }
                catch (IllegalArgumentException e) {
                    throw new SemanticException(TYPE_MISMATCH, node, "No literal form for type %s", type);
                }
            }

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitEnumLiteral(EnumLiteral node, StackableAstVisitorContext<Context> context)
        {
            Type type;
            try {
                type = functionAndTypeResolver.getType(parseTypeSignature(node.getType()));
            }
            catch (IllegalArgumentException | UnknownTypeException e) {
                throw new SemanticException(TYPE_MISMATCH, node, "Unknown type: " + node.getType());
            }

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitTimeLiteral(TimeLiteral node, StackableAstVisitorContext<Context> context)
        {
            boolean hasTimeZone;
            try {
                hasTimeZone = timeHasTimeZone(node.getValue());
            }
            catch (IllegalArgumentException e) {
                throw new SemanticException(INVALID_LITERAL, node, "'%s' is not a valid time literal", node.getValue());
            }
            Type type = hasTimeZone ? TIME_WITH_TIME_ZONE : TIME;
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitTimestampLiteral(TimestampLiteral node, StackableAstVisitorContext<Context> context)
        {
            try {
                if (sqlFunctionProperties.isLegacyTimestamp()) {
                    parseTimestampLiteral(sqlFunctionProperties.getTimeZoneKey(), node.getValue());
                }
                else {
                    parseTimestampLiteral(node.getValue());
                }
            }
            catch (Exception e) {
                throw new SemanticException(INVALID_LITERAL, node, "'%s' is not a valid timestamp literal", node.getValue());
            }

            Type type;
            if (timestampHasTimeZone(node.getValue())) {
                type = TIMESTAMP_WITH_TIME_ZONE;
            }
            else {
                type = TIMESTAMP;
            }
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitIntervalLiteral(IntervalLiteral node, StackableAstVisitorContext<Context> context)
        {
            Type type;
            if (node.isYearToMonth()) {
                type = INTERVAL_YEAR_MONTH;
            }
            else {
                type = INTERVAL_DAY_TIME;
            }
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitNullLiteral(NullLiteral node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, UNKNOWN);
        }

        @Override
        protected Type visitFunctionCall(FunctionCall node, StackableAstVisitorContext<Context> context)
        {
            if (node.getWindow().isPresent()) {
                Window window = node.getWindow().get();
                for (Expression expression : window.getPartitionBy()) {
                    process(expression, context);
                    Type type = getExpressionType(expression);
                    if (!type.isComparable()) {
                        throw new SemanticException(TYPE_MISMATCH, node, "%s is not comparable, and therefore cannot be used in window function PARTITION BY", type);
                    }
                }

                for (SortItem sortItem : getSortItemsFromOrderBy(window.getOrderBy())) {
                    process(sortItem.getSortKey(), context);
                    Type type = getExpressionType(sortItem.getSortKey());
                    if (!type.isOrderable()) {
                        throw new SemanticException(TYPE_MISMATCH, node, "%s is not orderable, and therefore cannot be used in window function ORDER BY", type);
                    }
                }

                if (window.getFrame().isPresent()) {
                    WindowFrame frame = window.getFrame().get();

                    if (frame.getType() == ROWS) {
                        if (frame.getStart().getValue().isPresent()) {
                            Expression startValue = frame.getStart().getValue().get();
                            Type type = process(startValue, context);
                            if (!type.equals(INTEGER) && !type.equals(BIGINT)) {
                                throw new SemanticException(TYPE_MISMATCH, node, "Window frame ROWS start value type must be INTEGER or BIGINT (actual %s)", type);
                            }
                        }
                        if (frame.getEnd().isPresent() && frame.getEnd().get().getValue().isPresent()) {
                            Expression endValue = frame.getEnd().get().getValue().get();
                            Type type = process(endValue, context);
                            if (!type.equals(INTEGER) && !type.equals(BIGINT)) {
                                throw new SemanticException(TYPE_MISMATCH, node, "Window frame ROWS end value type must be INTEGER or BIGINT (actual %s)", type);
                            }
                        }
                    }
                    else if (frame.getType() == RANGE) {
                        if (frame.getStart().getValue().isPresent()) {
                            Expression startValue = frame.getStart().getValue().get();
                            analyzeFrameRangeOffset(startValue, frame.getStart().getType(), context, window);
                        }
                        if (frame.getEnd().isPresent() && frame.getEnd().get().getValue().isPresent()) {
                            Expression endValue = frame.getEnd().get().getValue().get();
                            analyzeFrameRangeOffset(endValue, frame.getEnd().get().getType(), context, window);
                        }
                    }
                    else if (frame.getType() == GROUPS) {
                        if (frame.getStart().getValue().isPresent()) {
                            if (!window.getOrderBy().isPresent()) {
                                throw new SemanticException(MISSING_ORDER_BY, window, "Window frame of type GROUPS PRECEDING or FOLLOWING requires ORDER BY");
                            }
                            Expression startValue = frame.getStart().getValue().get();
                            Type type = process(startValue, context);
                            if (!type.equals(INTEGER) && !type.equals(BIGINT)) {
                                throw new SemanticException(TYPE_MISMATCH, node, "Window frame GROUPS start value type must be INTEGER or BIGINT (actual %s)", type);
                            }
                        }
                        if (frame.getEnd().isPresent() && frame.getEnd().get().getValue().isPresent()) {
                            if (!window.getOrderBy().isPresent()) {
                                throw new SemanticException(MISSING_ORDER_BY, window, "Window frame of type GROUPS PRECEDING or FOLLOWING requires ORDER BY");
                            }
                            Expression endValue = frame.getEnd().get().getValue().get();
                            Type type = process(endValue, context);
                            if (!type.equals(INTEGER) && !type.equals(BIGINT)) {
                                throw new SemanticException(TYPE_MISMATCH, node, "Window frame GROUPS end value type must be INTEGER or BIGINT (actual %s)", type);
                            }
                        }
                    }
                    else {
                        throw new SemanticException(NOT_SUPPORTED, frame, "Unsupported frame type: " + frame.getType());
                    }
                }

                windowFunctions.add(NodeRef.of(node));
            }

            if (node.getFilter().isPresent()) {
                Expression expression = node.getFilter().get();
                process(expression, context);
            }

            ImmutableList.Builder<TypeSignatureProvider> argumentTypesBuilder = ImmutableList.builder();
            for (int index = 0; index < node.getArguments().size(); ++index) {
                Expression expression = node.getArguments().get(index);
                if (expression instanceof LambdaExpression || expression instanceof BindExpression) {
                    argumentTypesBuilder.add(new TypeSignatureProvider(
                            types -> {
                                ExpressionAnalyzer innerExpressionAnalyzer = new ExpressionAnalyzer(
                                        functionAndTypeResolver,
                                        statementAnalyzerFactory,
                                        sessionFunctions,
                                        transactionId,
                                        sqlFunctionProperties,
                                        symbolTypes,
                                        parameters,
                                        warningCollector,
                                        isDescribe,
                                        outerScopeSymbolTypes);
                                if (context.getContext().isInLambda()) {
                                    for (LambdaArgumentDeclaration argument : context.getContext().getFieldToLambdaArgumentDeclaration().values()) {
                                        innerExpressionAnalyzer.setExpressionType(argument, getExpressionType(argument));
                                    }
                                }
                                Type type = innerExpressionAnalyzer.analyze(expression, baseScope, context.getContext().expectingLambda(types, ImmutableMap.of()));
                                if (expression instanceof LambdaExpression) {
                                    verifyNoAggregateWindowOrGroupingFunctions(
                                            innerExpressionAnalyzer.getResolvedFunctions(),
                                            functionAndTypeResolver,
                                            ((LambdaExpression) expression).getBody(),
                                            "Lambda expression");
                                    verifyNoExternalFunctions(innerExpressionAnalyzer.getResolvedFunctions(), functionAndTypeResolver, ((LambdaExpression) expression).getBody(), "Lambda expression");
                                }
                                return type.getTypeSignature();
                            }));
                }
                else {
                    Context newContext = context.getContext();
                    if (isUnusedArgumentForAccessControl(node, index, context.getContext())) {
                        newContext = newContext.withUnusedExpressionsForAccessControl(ImmutableSet.of(NodeRef.of(expression)));
                    }
                    argumentTypesBuilder.add(new TypeSignatureProvider(process(expression, new StackableAstVisitorContext<>(newContext)).getTypeSignature()));
                }
            }

            ImmutableList<TypeSignatureProvider> argumentTypes = argumentTypesBuilder.build();
            FunctionHandle function = resolveFunction(sessionFunctions, transactionId, node, argumentTypes, functionAndTypeResolver);
            FunctionMetadata functionMetadata = functionAndTypeResolver.getFunctionMetadata(function);

            if (node.getOrderBy().isPresent()) {
                for (SortItem sortItem : node.getOrderBy().get().getSortItems()) {
                    Type sortKeyType = process(sortItem.getSortKey(), context);
                    if (!sortKeyType.isOrderable()) {
                        throw new SemanticException(TYPE_MISMATCH, node, "ORDER BY can only be applied to orderable types (actual: %s)", sortKeyType.getDisplayName());
                    }
                }
            }

            if (node.isIgnoreNulls() && node.getWindow().isPresent()) {
                if (!functionResolution.isWindowValueFunction(function)) {
                    String warningMessage = createWarningMessage(node, "IGNORE NULLS is not used for aggregate and ranking window functions. This will cause queries to fail in future versions.");
                    warningCollector.add(new PrestoWarning(SEMANTIC_WARNING, warningMessage));
                }
            }

            Map<Identifier, ResolvedSubfield> resolvedLambdaArguments = getResolvedLambdaArguments(node, context, expressionTypes);

            for (int i = 0; i < node.getArguments().size(); i++) {
                Expression expression = node.getArguments().get(i);
                Type expectedType = functionAndTypeResolver.getType(functionMetadata.getArgumentTypes().get(i));
                requireNonNull(expectedType, format("Type %s not found", functionMetadata.getArgumentTypes().get(i)));
                if (node.isDistinct() && !expectedType.isComparable()) {
                    throw new SemanticException(TYPE_MISMATCH, node, "DISTINCT can only be applied to comparable types (actual: %s)", expectedType);
                }
                if (argumentTypes.get(i).hasDependency()) {
                    FunctionType expectedFunctionType = (FunctionType) expectedType;
                    process(expression, new StackableAstVisitorContext<>(context.getContext().expectingLambda(expectedFunctionType.getArgumentTypes(), resolvedLambdaArguments)));
                }
                else {
                    Type actualType = functionAndTypeResolver.getType(argumentTypes.get(i).getTypeSignature());
                    coerceType(expression, actualType, expectedType, format("Function %s argument %d", function, i));
                }
            }
            resolvedFunctions.put(NodeRef.of(node), function);

            if (functionMetadata.getName().equals(QualifiedObjectName.valueOf(JAVA_BUILTIN_NAMESPACE, "REDUCE_AGG"))) {
                Expression initialValueArg = node.getArguments().get(1);
                // For builtin reduce_agg, we make sure the initial value is not null as we cannot handle null properly now.

                if (!isNonNullConstant(initialValueArg)) {
                    throw new SemanticException(INVALID_PROCEDURE_ARGUMENTS, initialValueArg, "REDUCE_AGG only supports non-NULL literal as the initial value", initialValueArg);
                }
            }

            Type type = functionAndTypeResolver.getType(functionMetadata.getReturnType());

            if (type instanceof MapType) {
                Type keyType = ((MapType) type).getKeyType();
                if (TypeUtils.isApproximateNumericType(keyType)) {
                    String warningMessage = createWarningMessage(node, "Map keys with real/double type can be non-deterministic. Please use decimal type instead");
                    warningCollector.add(new PrestoWarning(SEMANTIC_WARNING, warningMessage));
                }
            }

            return setExpressionType(node, type);
        }

        private String createWarningMessage(Node node, String message)
        {
            if (node.getLocation().isPresent()) {
                return format("%s Expression:%s line %s:%s", message, node, node.getLocation().get().getLineNumber(), node.getLocation().get().getColumnNumber());
            }
            else {
                return format("%s Expression:%s", message, node);
            }
        }

        private void analyzeFrameRangeOffset(Expression offsetValue, FrameBound.Type boundType, StackableAstVisitorContext<Context> context, Window window)
        {
            if (!window.getOrderBy().isPresent()) {
                throw new SemanticException(MISSING_ORDER_BY, window, "Window frame of type RANGE PRECEDING or FOLLOWING requires ORDER BY");
            }
            OrderBy orderBy = window.getOrderBy().get();
            if (orderBy.getSortItems().size() != 1) {
                throw new SemanticException(INVALID_ORDER_BY, orderBy, "Window frame of type RANGE PRECEDING or FOLLOWING requires single sort item in ORDER BY (actual: %s)", orderBy.getSortItems().size());
            }
            Expression sortKey = Iterables.getOnlyElement(orderBy.getSortItems()).getSortKey();
            Type sortKeyType = getExpressionType(sortKey);
            if (!isNumericType(sortKeyType) && !isDateTimeType(sortKeyType)) {
                throw new SemanticException(TYPE_MISMATCH, sortKey, "Window frame of type RANGE PRECEDING or FOLLOWING requires that sort item type be numeric, datetime or interval (actual: %s)", sortKeyType);
            }

            Type offsetValueType = process(offsetValue, context);

            if (isNumericType(sortKeyType)) {
                if (!isNumericType(offsetValueType)) {
                    throw new SemanticException(TYPE_MISMATCH, offsetValue, "Window frame RANGE value type (%s) not compatible with sort item type (%s)", offsetValueType, sortKeyType);
                }
            }
            else { // isDateTimeType(sortKeyType)
                if (offsetValueType != INTERVAL_DAY_TIME && offsetValueType != INTERVAL_YEAR_MONTH) {
                    throw new SemanticException(TYPE_MISMATCH, offsetValue, "Window frame RANGE value type (%s) not compatible with sort item type (%s)", offsetValueType, sortKeyType);
                }
            }

            // resolve function to calculate frame boundary value (add / subtract offset from sortKey)
            SortItem.Ordering ordering = Iterables.getOnlyElement(orderBy.getSortItems()).getOrdering();
            OperatorType operatorType;
            FunctionHandle function;
            if ((boundType == PRECEDING && ordering == ASCENDING) || (boundType == FOLLOWING && ordering == DESCENDING)) {
                operatorType = SUBTRACT;
            }
            else {
                operatorType = ADD;
            }
            try {
                function = functionAndTypeResolver.resolveOperator(operatorType, TypeSignatureProvider.fromTypes(sortKeyType, offsetValueType));
            }
            catch (PrestoException e) {
                ErrorCode errorCode = e.getErrorCode();
                if (errorCode.equals(OPERATOR_NOT_FOUND.toErrorCode())) {
                    throw new SemanticException(TYPE_MISMATCH, offsetValue, "Window frame RANGE value type (%s) not compatible with sort item type (%s)", offsetValueType, sortKeyType);
                }
                throw e;
            }

            FunctionMetadata functionMetadata = functionAndTypeResolver.getFunctionMetadata(function);
            Type expectedSortKeyType = functionAndTypeResolver.getType(functionMetadata.getArgumentTypes().get(0));

            if (!expectedSortKeyType.equals(sortKeyType)) {
                if (!functionAndTypeResolver.canCoerce(sortKeyType, expectedSortKeyType)) {
                    throw new SemanticException(TYPE_MISMATCH, sortKey, "Sort key must evaluate to a %s (actual: %s)", expectedSortKeyType, sortKeyType);
                }
                sortKeyCoercionsForFrameBoundCalculation.put(NodeRef.of(offsetValue), expectedSortKeyType);
            }

            Type expectedOffsetValueType = functionAndTypeResolver.getType(functionMetadata.getArgumentTypes().get(1));
            if (!expectedOffsetValueType.equals(offsetValueType)) {
                coerceType(offsetValue, offsetValueType, expectedOffsetValueType, format("Function %s argument 1", function));
            }
            Type expectedFunctionResultType = functionAndTypeResolver.getType(functionMetadata.getReturnType());
            if (!expectedFunctionResultType.equals(sortKeyType)) {
                if (!functionAndTypeResolver.canCoerce(sortKeyType, expectedFunctionResultType)) {
                    throw new SemanticException(TYPE_MISMATCH, sortKey, "Sort key must evaluate to a %s (actual: %s)", expectedFunctionResultType, sortKeyType);
                }
                sortKeyCoercionsForFrameBoundComparison.put(NodeRef.of(offsetValue), expectedFunctionResultType);
            }

            frameBoundCalculations.put(NodeRef.of(offsetValue), function);
        }

        @Override
        protected Type visitAtTimeZone(AtTimeZone node, StackableAstVisitorContext<Context> context)
        {
            Type valueType = process(node.getValue(), context);
            process(node.getTimeZone(), context);
            if (!valueType.equals(TIME_WITH_TIME_ZONE) && !valueType.equals(TIMESTAMP_WITH_TIME_ZONE) && !valueType.equals(TIME) && !valueType.equals(TIMESTAMP)) {
                throw new SemanticException(TYPE_MISMATCH, node.getValue(), "Type of value must be a time or timestamp with or without time zone (actual %s)", valueType);
            }
            Type resultType = valueType;
            if (valueType.equals(TIME)) {
                resultType = TIME_WITH_TIME_ZONE;
            }
            else if (valueType.equals(TIMESTAMP)) {
                resultType = TIMESTAMP_WITH_TIME_ZONE;
            }

            return setExpressionType(node, resultType);
        }

        @Override
        protected Type visitCurrentUser(CurrentUser node, StackableAstVisitorContext<Context> context)
        {
            return setExpressionType(node, VARCHAR);
        }

        @Override
        protected Type visitParameter(Parameter node, StackableAstVisitorContext<Context> context)
        {
            if (isDescribe) {
                return setExpressionType(node, UNKNOWN);
            }
            if (parameters.size() == 0) {
                throw new SemanticException(INVALID_PARAMETER_USAGE, node, "query takes no parameters");
            }
            if (node.getPosition() >= parameters.size()) {
                throw new SemanticException(INVALID_PARAMETER_USAGE, node, "invalid parameter index %s, max value is %s", node.getPosition(), parameters.size() - 1);
            }

            Type resultType = process(parameters.get(NodeRef.of(node)), context);
            return setExpressionType(node, resultType);
        }

        @Override
        protected Type visitExtract(Extract node, StackableAstVisitorContext<Context> context)
        {
            Type type = process(node.getExpression(), context);
            if (!isDateTimeType(type)) {
                throw new SemanticException(TYPE_MISMATCH, node.getExpression(), "Type of argument to extract must be DATE, TIME, TIMESTAMP, or INTERVAL (actual %s)", type);
            }
            Extract.Field field = node.getField();
            if ((field == TIMEZONE_HOUR || field == TIMEZONE_MINUTE) && !(type.equals(TIME_WITH_TIME_ZONE) || type.equals(TIMESTAMP_WITH_TIME_ZONE))) {
                throw new SemanticException(TYPE_MISMATCH, node.getExpression(), "Type of argument to extract time zone field must have a time zone (actual %s)", type);
            }

            return setExpressionType(node, BIGINT);
        }

        private boolean isDateTimeType(Type type)
        {
            return type.equals(DATE) ||
                    type.equals(TIME) ||
                    type.equals(TIME_WITH_TIME_ZONE) ||
                    type.equals(TIMESTAMP) ||
                    type.equals(TIMESTAMP_WITH_TIME_ZONE) ||
                    type.equals(INTERVAL_DAY_TIME) ||
                    type.equals(INTERVAL_YEAR_MONTH);
        }

        @Override
        protected Type visitBetweenPredicate(BetweenPredicate node, StackableAstVisitorContext<Context> context)
        {
            return getOperator(context, node, OperatorType.BETWEEN, node.getValue(), node.getMin(), node.getMax());
        }

        @Override
        public Type visitTryExpression(TryExpression node, StackableAstVisitorContext<Context> context)
        {
            Type type = process(node.getInnerExpression(), context);
            return setExpressionType(node, type);
        }

        @Override
        public Type visitCast(Cast node, StackableAstVisitorContext<Context> context)
        {
            Type type;
            try {
                type = functionAndTypeResolver.getType(parseTypeSignature(node.getType()));
            }
            catch (IllegalArgumentException | UnknownTypeException e) {
                throw new SemanticException(TYPE_MISMATCH, node, "Unknown type: " + node.getType());
            }

            if (type.equals(UNKNOWN)) {
                throw new SemanticException(TYPE_MISMATCH, node, "UNKNOWN is not a valid type");
            }

            Type value = process(node.getExpression(), context);
            if (!value.equals(UNKNOWN) && !node.isTypeOnly()) {
                try {
                    functionAndTypeResolver.lookupCast("CAST", value, type);
                }
                catch (OperatorNotFoundException e) {
                    throw new SemanticException(TYPE_MISMATCH, node, "Cannot cast %s to %s", value, type);
                }
            }

            return setExpressionType(node, type);
        }

        @Override
        protected Type visitInPredicate(InPredicate node, StackableAstVisitorContext<Context> context)
        {
            Expression value = node.getValue();
            process(value, context);

            Expression valueList = node.getValueList();
            process(valueList, context);

            if (valueList instanceof InListExpression) {
                InListExpression inListExpression = (InListExpression) valueList;

                coerceToSingleType(context,
                        "IN value and list items must be the same type: %s",
                        ImmutableList.<Expression>builder().add(value).addAll(inListExpression.getValues()).build());
            }
            else if (valueList instanceof SubqueryExpression) {
                coerceToSingleType(context, node, "value and result of subquery must be of the same type for IN expression: %s vs %s", value, valueList);
            }

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitInListExpression(InListExpression node, StackableAstVisitorContext<Context> context)
        {
            Type type = coerceToSingleType(context, "All IN list values must be the same type: %s", node.getValues());

            setExpressionType(node, type);
            return type; // TODO: this really should a be relation type
        }

        @Override
        protected Type visitSubqueryExpression(SubqueryExpression node, StackableAstVisitorContext<Context> context)
        {
            if (context.getContext().isInLambda()) {
                throw new SemanticException(NOT_SUPPORTED, node, "Lambda expression cannot contain subqueries");
            }
            StatementAnalyzer analyzer = statementAnalyzerFactory.apply(node);
            Scope subqueryScope = Scope.builder()
                    .withParent(context.getContext().getScope())
                    .build();
            Scope queryScope = analyzer.analyze(node.getQuery(), subqueryScope);

            // Subquery should only produce one column
            if (queryScope.getRelationType().getVisibleFieldCount() != 1) {
                throw new SemanticException(MULTIPLE_FIELDS_FROM_SUBQUERY,
                        node,
                        "Multiple columns returned by subquery are not yet supported. Found %s",
                        queryScope.getRelationType().getVisibleFieldCount());
            }

            Node previousNode = context.getPreviousNode().orElse(null);
            if (previousNode instanceof InPredicate && ((InPredicate) previousNode).getValue() != node) {
                subqueryInPredicates.add(NodeRef.of((InPredicate) previousNode));
            }
            else if (previousNode instanceof QuantifiedComparisonExpression) {
                quantifiedComparisons.add(NodeRef.of((QuantifiedComparisonExpression) previousNode));
            }
            else {
                scalarSubqueries.add(NodeRef.of(node));
            }

            Type type = getOnlyElement(queryScope.getRelationType().getVisibleFields()).getType();
            return setExpressionType(node, type);
        }

        @Override
        protected Type visitExists(ExistsPredicate node, StackableAstVisitorContext<Context> context)
        {
            StatementAnalyzer analyzer = statementAnalyzerFactory.apply(node);
            Scope subqueryScope = Scope.builder().withParent(context.getContext().getScope()).build();
            analyzer.analyze(node.getSubquery(), subqueryScope);

            existsSubqueries.add(NodeRef.of(node));

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        protected Type visitQuantifiedComparisonExpression(QuantifiedComparisonExpression node, StackableAstVisitorContext<Context> context)
        {
            Expression value = node.getValue();
            process(value, context);

            Expression subquery = node.getSubquery();
            process(subquery, context);

            Type comparisonType = coerceToSingleType(context, node, "Value expression and result of subquery must be of the same type for quantified comparison: %s vs %s", value, subquery);

            switch (node.getOperator()) {
                case LESS_THAN:
                case LESS_THAN_OR_EQUAL:
                case GREATER_THAN:
                case GREATER_THAN_OR_EQUAL:
                    if (!comparisonType.isOrderable()) {
                        throw new SemanticException(TYPE_MISMATCH, node, "Type [%s] must be orderable in order to be used in quantified comparison", comparisonType);
                    }
                    break;
                case EQUAL:
                case NOT_EQUAL:
                    if (!comparisonType.isComparable()) {
                        throw new SemanticException(TYPE_MISMATCH, node, "Type [%s] must be comparable in order to be used in quantified comparison", comparisonType);
                    }
                    break;
                default:
                    throw new IllegalStateException(format("Unexpected comparison type: %s", node.getOperator()));
            }

            return setExpressionType(node, BOOLEAN);
        }

        @Override
        public Type visitFieldReference(FieldReference node, StackableAstVisitorContext<Context> context)
        {
            Field field = baseScope.getRelationType().getFieldByIndex(node.getFieldIndex());
            return handleResolvedField(node, new FieldId(baseScope.getRelationId(), node.getFieldIndex()), field, context);
        }

        @Override
        protected Type visitLambdaExpression(LambdaExpression node, StackableAstVisitorContext<Context> context)
        {
            if (!context.getContext().isExpectingLambda()) {
                throw new SemanticException(STANDALONE_LAMBDA, node, "Lambda expression should always be used inside a function");
            }

            List<Type> types = context.getContext().getFunctionInputTypes();
            List<LambdaArgumentDeclaration> lambdaArguments = node.getArguments();

            if (types.size() != lambdaArguments.size()) {
                throw new SemanticException(INVALID_PARAMETER_USAGE, node,
                        format("Expected a lambda that takes %s argument(s) but got %s", types.size(), lambdaArguments.size()));
            }

            ImmutableList.Builder<Field> fields = ImmutableList.builder();
            for (int i = 0; i < lambdaArguments.size(); i++) {
                LambdaArgumentDeclaration lambdaArgument = lambdaArguments.get(i);
                Type type = types.get(i);
                fields.add(com.facebook.presto.sql.analyzer.Field.newUnqualified(lambdaArgument.getLocation(), lambdaArgument.getName().getValue(), type));
                setExpressionType(lambdaArgument, type);
            }

            Scope lambdaScope = Scope.builder()
                    .withParent(context.getContext().getScope())
                    .withRelationType(RelationId.of(node), new RelationType(fields.build()))
                    .build();

            ImmutableMap.Builder<FieldId, LambdaArgumentDeclaration> fieldToLambdaArgumentDeclaration = ImmutableMap.builder();
            if (context.getContext().isInLambda()) {
                fieldToLambdaArgumentDeclaration.putAll(context.getContext().getFieldToLambdaArgumentDeclaration());
            }
            for (LambdaArgumentDeclaration lambdaArgument : lambdaArguments) {
                QualifiedName name = QualifiedName.of(ImmutableList.of(new Identifier(lambdaArgument.getName().getValue(), lambdaArgument.getName().isDelimited())));
                ResolvedField resolvedField = lambdaScope.resolveField(lambdaArgument, name);
                fieldToLambdaArgumentDeclaration.put(FieldId.from(resolvedField), lambdaArgument);
            }

            Type returnType = process(node.getBody(), new StackableAstVisitorContext<>(context.getContext().inLambda(lambdaScope, fieldToLambdaArgumentDeclaration.build())));
            FunctionType functionType = new FunctionType(types, returnType);
            return setExpressionType(node, functionType);
        }

        @Override
        protected Type visitBindExpression(BindExpression node, StackableAstVisitorContext<Context> context)
        {
            verify(context.getContext().isExpectingLambda(), "bind expression found when lambda is not expected");

            StackableAstVisitorContext<Context> innerContext = new StackableAstVisitorContext<>(context.getContext().notExpectingLambda());
            ImmutableList.Builder<Type> functionInputTypesBuilder = ImmutableList.builder();
            for (Expression value : node.getValues()) {
                functionInputTypesBuilder.add(process(value, innerContext));
            }
            functionInputTypesBuilder.addAll(context.getContext().getFunctionInputTypes());
            List<Type> functionInputTypes = functionInputTypesBuilder.build();

            FunctionType functionType = (FunctionType) process(node.getFunction(), new StackableAstVisitorContext<>(context.getContext().expectingLambda(functionInputTypes, ImmutableMap.of())));

            List<Type> argumentTypes = functionType.getArgumentTypes();
            int numCapturedValues = node.getValues().size();
            verify(argumentTypes.size() == functionInputTypes.size());
            for (int i = 0; i < numCapturedValues; i++) {
                verify(functionInputTypes.get(i).equals(argumentTypes.get(i)));
            }

            FunctionType result = new FunctionType(argumentTypes.subList(numCapturedValues, argumentTypes.size()), functionType.getReturnType());
            return setExpressionType(node, result);
        }

        @Override
        protected Type visitExpression(Expression node, StackableAstVisitorContext<Context> context)
        {
            throw new SemanticException(NOT_SUPPORTED, node, "not yet implemented: " + node.getClass().getName());
        }

        @Override
        protected Type visitNode(Node node, StackableAstVisitorContext<Context> context)
        {
            throw new SemanticException(NOT_SUPPORTED, node, "not yet implemented: " + node.getClass().getName());
        }

        @Override
        public Type visitGroupingOperation(GroupingOperation node, StackableAstVisitorContext<Context> context)
        {
            if (node.getGroupingColumns().size() > MAX_NUMBER_GROUPING_ARGUMENTS_BIGINT) {
                throw new SemanticException(INVALID_PROCEDURE_ARGUMENTS, node, String.format("GROUPING supports up to %d column arguments", MAX_NUMBER_GROUPING_ARGUMENTS_BIGINT));
            }

            for (Expression columnArgument : node.getGroupingColumns()) {
                process(columnArgument, context);
            }

            if (node.getGroupingColumns().size() <= MAX_NUMBER_GROUPING_ARGUMENTS_INTEGER) {
                return setExpressionType(node, INTEGER);
            }
            else {
                return setExpressionType(node, BIGINT);
            }
        }

        private void addColumnSubfieldReferences(Expression node, StackableAstVisitorContext<Context> context)
        {
            Optional<ResolvedSubfield> resolvedSubfield = resolveSubfield(node, context, expressionTypes);
            if (!resolvedSubfield.isPresent()) {
                return;
            }
            tableColumnAndSubfieldReferences.put(
                    resolvedSubfield.get().getResolvedField().getField().getOriginTable().get(),
                    resolvedSubfield.get().getSubfield());

            if (!context.getContext().getUnusedExpressionsForAccessControl().contains(NodeRef.of(node))) {
                tableColumnAndSubfieldReferencesForAccessControl.put(
                        resolvedSubfield.get().getResolvedField().getField().getOriginTable().get(),
                        resolvedSubfield.get().getSubfield());
            }
        }

        private Type getOperator(StackableAstVisitorContext<Context> context, Expression node, OperatorType operatorType, Expression... arguments)
        {
            ImmutableList.Builder<Type> argumentTypes = ImmutableList.builder();
            for (Expression expression : arguments) {
                argumentTypes.add(process(expression, context));
            }

            FunctionMetadata operatorMetadata;
            try {
                operatorMetadata = functionAndTypeResolver.getFunctionMetadata(functionAndTypeResolver.resolveOperator(operatorType, fromTypes(argumentTypes.build())));
            }
            catch (OperatorNotFoundException e) {
                throw new SemanticException(TYPE_MISMATCH, node, "%s", e.getMessage());
            }
            catch (PrestoException e) {
                if (e.getErrorCode().getCode() == StandardErrorCode.AMBIGUOUS_FUNCTION_CALL.toErrorCode().getCode()) {
                    throw new SemanticException(SemanticErrorCode.AMBIGUOUS_FUNCTION_CALL, node, e.getMessage());
                }
                throw e;
            }

            for (int i = 0; i < arguments.length; i++) {
                Expression expression = arguments[i];
                Type type = functionAndTypeResolver.getType(operatorMetadata.getArgumentTypes().get(i));
                coerceType(context, expression, type, format("Operator %s argument %d", operatorMetadata, i));
            }

            Type type = functionAndTypeResolver.getType(operatorMetadata.getReturnType());
            return setExpressionType(node, type);
        }

        private void coerceType(Expression expression, Type actualType, Type expectedType, String message)
        {
            if (!actualType.equals(expectedType)) {
                if (!functionAndTypeResolver.canCoerce(actualType, expectedType)) {
                    throw new SemanticException(TYPE_MISMATCH, expression, message + " must evaluate to a %s (actual: %s)", expectedType, actualType);
                }
                addOrReplaceExpressionCoercion(expression, actualType, expectedType);
            }
        }

        private void coerceType(StackableAstVisitorContext<Context> context, Expression expression, Type expectedType, String message)
        {
            Type actualType = process(expression, context);
            coerceType(expression, actualType, expectedType, message);
        }

        private Type coerceToSingleType(StackableAstVisitorContext<Context> context, Node node, String message, Expression first, Expression second)
        {
            Type firstType = UNKNOWN;
            if (first != null) {
                firstType = process(first, context);
            }
            Type secondType = UNKNOWN;
            if (second != null) {
                secondType = process(second, context);
            }

            // coerce types if possible
            Optional<Type> superTypeOptional = functionAndTypeResolver.getCommonSuperType(firstType, secondType);
            if (superTypeOptional.isPresent()
                    && functionAndTypeResolver.canCoerce(firstType, superTypeOptional.get())
                    && functionAndTypeResolver.canCoerce(secondType, superTypeOptional.get())) {
                Type superType = superTypeOptional.get();
                if (!firstType.equals(superType)) {
                    addOrReplaceExpressionCoercion(first, firstType, superType);
                }
                if (!secondType.equals(superType)) {
                    addOrReplaceExpressionCoercion(second, secondType, superType);
                }
                return superType;
            }

            throw new SemanticException(TYPE_MISMATCH, node, message, firstType, secondType);
        }

        private Type coerceToSingleType(StackableAstVisitorContext<Context> context, String message, List<Expression> expressions)
        {
            // determine super type
            Type superType = UNKNOWN;
            for (Expression expression : expressions) {
                Optional<Type> newSuperType = functionAndTypeResolver.getCommonSuperType(superType, process(expression, context));
                if (!newSuperType.isPresent()) {
                    throw new SemanticException(TYPE_MISMATCH, expression, message, superType.getDisplayName());
                }
                superType = newSuperType.get();
            }

            // verify all expressions can be coerced to the superType
            for (Expression expression : expressions) {
                Type type = process(expression, context);
                if (!type.equals(superType)) {
                    if (!functionAndTypeResolver.canCoerce(type, superType)) {
                        throw new SemanticException(TYPE_MISMATCH, expression, message, superType.getDisplayName());
                    }
                    addOrReplaceExpressionCoercion(expression, type, superType);
                }
            }

            return superType;
        }

        private void addOrReplaceExpressionCoercion(Expression expression, Type type, Type superType)
        {
            NodeRef<Expression> ref = NodeRef.of(expression);
            expressionCoercions.put(ref, superType);
            if (functionAndTypeResolver.isTypeOnlyCoercion(type, superType)) {
                typeOnlyCoercions.add(ref);
            }
            else if (typeOnlyCoercions.contains(ref)) {
                typeOnlyCoercions.remove(ref);
            }
        }
    }

    public static class Context
    {
        private final Scope scope;

        // functionInputTypes and nameToLambdaDeclarationMap can be null or non-null independently. All 4 combinations are possible.

        // The list of types when expecting a lambda (i.e. processing lambda parameters of a function); null otherwise.
        // Empty list represents expecting a lambda with no arguments.
        private final List<Type> functionInputTypes;
        // The mapping from names to corresponding lambda argument declarations when inside a lambda; null otherwise.
        // Empty map means that the all lambda expressions surrounding the current node has no arguments.
        private final Map<FieldId, LambdaArgumentDeclaration> fieldToLambdaArgumentDeclaration;
        private final Map<Identifier, ResolvedSubfield> resolvedLambdaArguments;
        private final Set<NodeRef<Expression>> unusedExpressionsForAccessControl;

        private Context(
                Scope scope,
                List<Type> functionInputTypes,
                Map<FieldId, LambdaArgumentDeclaration> fieldToLambdaArgumentDeclaration,
                Map<Identifier, ResolvedSubfield> resolvedLambdaArguments,
                Set<NodeRef<Expression>> unusedExpressionsForAccessControl)
        {
            this.scope = requireNonNull(scope, "scope is null");
            this.functionInputTypes = functionInputTypes;
            this.fieldToLambdaArgumentDeclaration = fieldToLambdaArgumentDeclaration;
            this.resolvedLambdaArguments = requireNonNull(resolvedLambdaArguments, "resolvedLambdaArguments is null");
            this.unusedExpressionsForAccessControl = requireNonNull(unusedExpressionsForAccessControl, "unusedExpressionsForAccessControl is null");
        }

        public static Context notInLambda(Scope scope)
        {
            return new Context(scope, null, null, ImmutableMap.of(), ImmutableSet.of());
        }

        public Context inLambda(Scope scope, Map<FieldId, LambdaArgumentDeclaration> fieldToLambdaArgumentDeclaration)
        {
            return new Context(
                    scope,
                    null,
                    requireNonNull(fieldToLambdaArgumentDeclaration, "fieldToLambdaArgumentDeclaration is null"),
                    resolvedLambdaArguments,
                    unusedExpressionsForAccessControl);
        }

        public Context expectingLambda(List<Type> functionInputTypes, Map<Identifier, ResolvedSubfield> resolvedLambdaArguments)
        {
            return new Context(
                    scope,
                    requireNonNull(functionInputTypes, "functionInputTypes is null"),
                    this.fieldToLambdaArgumentDeclaration,
                    resolvedLambdaArguments,
                    unusedExpressionsForAccessControl);
        }

        public Context notExpectingLambda()
        {
            return new Context(scope, null, this.fieldToLambdaArgumentDeclaration, ImmutableMap.of(), unusedExpressionsForAccessControl);
        }

        public Context withUnusedExpressionsForAccessControl(Set<NodeRef<Expression>> unusedExpressionsForAccessControl)
        {
            return new Context(
                    scope,
                    functionInputTypes,
                    fieldToLambdaArgumentDeclaration,
                    resolvedLambdaArguments,
                    Sets.union(unusedExpressionsForAccessControl, this.unusedExpressionsForAccessControl));
        }

        Scope getScope()
        {
            return scope;
        }

        public boolean isInLambda()
        {
            return fieldToLambdaArgumentDeclaration != null;
        }

        public boolean isExpectingLambda()
        {
            return functionInputTypes != null;
        }

        public Map<FieldId, LambdaArgumentDeclaration> getFieldToLambdaArgumentDeclaration()
        {
            checkState(isInLambda());
            return fieldToLambdaArgumentDeclaration;
        }

        public List<Type> getFunctionInputTypes()
        {
            checkState(isExpectingLambda());
            return functionInputTypes;
        }

        public Map<Identifier, ResolvedSubfield> getResolvedLambdaArguments()
        {
            return resolvedLambdaArguments;
        }

        public Set<NodeRef<Expression>> getUnusedExpressionsForAccessControl()
        {
            return unusedExpressionsForAccessControl;
        }
    }

    public static FunctionHandle resolveFunction(
            Optional<Map<SqlFunctionId, SqlInvokedFunction>> sessionFunctions,
            Optional<TransactionId> transactionId,
            FunctionCall node,
            List<TypeSignatureProvider> argumentTypes,
            FunctionAndTypeResolver functionAndTypeResolver)
    {
        try {
            return functionAndTypeResolver.resolveFunction(
                    sessionFunctions,
                    transactionId,
                    functionAndTypeResolver.qualifyObjectName(node.getName()),
                    argumentTypes);
        }
        catch (PrestoException e) {
            if (e.getErrorCode().getCode() == StandardErrorCode.FUNCTION_NOT_FOUND.toErrorCode().getCode()) {
                throw new SemanticException(SemanticErrorCode.FUNCTION_NOT_FOUND, node, e.getMessage());
            }
            if (e.getErrorCode().getCode() == StandardErrorCode.AMBIGUOUS_FUNCTION_CALL.toErrorCode().getCode()) {
                throw new SemanticException(SemanticErrorCode.AMBIGUOUS_FUNCTION_CALL, node, e.getMessage());
            }
            throw e;
        }
    }

    public static Map<NodeRef<Expression>, Type> getExpressionTypes(
            Session session,
            Metadata metadata,
            SqlParser sqlParser,
            TypeProvider types,
            Expression expression,
            Map<NodeRef<Parameter>, Expression> parameters,
            WarningCollector warningCollector)
    {
        return getExpressionTypes(session, metadata, sqlParser, types, expression, parameters, warningCollector, false);
    }

    public static Map<NodeRef<Expression>, Type> getExpressionTypes(
            Session session,
            Metadata metadata,
            SqlParser sqlParser,
            TypeProvider types,
            Expression expression,
            Map<NodeRef<Parameter>, Expression> parameters,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        return getExpressionTypes(session, metadata, sqlParser, types, ImmutableList.of(expression), parameters, warningCollector, isDescribe);
    }

    public static Map<NodeRef<Expression>, Type> getExpressionTypes(
            Session session,
            Metadata metadata,
            SqlParser sqlParser,
            TypeProvider types,
            Iterable<Expression> expressions,
            Map<NodeRef<Parameter>, Expression> parameters,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        return analyzeExpressions(session, metadata, sqlParser, types, expressions, parameters, warningCollector, isDescribe).getExpressionTypes();
    }

    public static ExpressionAnalysis analyzeExpressions(
            Session session,
            Metadata metadata,
            SqlParser sqlParser,
            TypeProvider types,
            Iterable<Expression> expressions,
            Map<NodeRef<Parameter>, Expression> parameters,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        // expressions at this point can not have sub queries so deny all access checks
        // in the future, we will need a full access controller here to verify access to functions
        Analysis analysis = new Analysis(null, parameters, isDescribe);
        ExpressionAnalyzer analyzer = create(analysis, session, metadata, sqlParser, new DenyAllAccessControl(), types, warningCollector);
        for (Expression expression : expressions) {
            analyzer.analyze(expression, Scope.builder().withRelationType(RelationId.anonymous(), new RelationType()).build());
        }

        return new ExpressionAnalysis(
                analyzer.getExpressionTypes(),
                analyzer.getExpressionCoercions(),
                analyzer.getSubqueryInPredicates(),
                analyzer.getScalarSubqueries(),
                analyzer.getExistsSubqueries(),
                analyzer.getColumnReferences(),
                analyzer.getTypeOnlyCoercions(),
                analyzer.getQuantifiedComparisons(),
                analyzer.getLambdaArgumentReferences(),
                analyzer.getWindowFunctions());
    }

    public static ExpressionAnalysis analyzeExpression(
            Session session,
            Metadata metadata,
            AccessControl accessControl,
            SqlParser sqlParser,
            Scope scope,
            Analysis analysis,
            Expression expression,
            WarningCollector warningCollector)
    {
        return analyzeExpression(session, metadata, accessControl, sqlParser, scope, TypeProvider.empty(), analysis, expression, warningCollector, ImmutableMap.of());
    }

    public static ExpressionAnalysis analyzeExpression(
            Session session,
            Metadata metadata,
            AccessControl accessControl,
            SqlParser sqlParser,
            Scope scope,
            TypeProvider types,
            Analysis analysis,
            Expression expression,
            WarningCollector warningCollector,
            Map<NodeRef<Expression>, Type> outerScopeSymbolTypes)
    {
        ExpressionAnalyzer analyzer = create(analysis, session, metadata, sqlParser, accessControl, types, warningCollector, outerScopeSymbolTypes);
        analyzer.analyze(expression, scope);

        Map<NodeRef<Expression>, Type> expressionTypes = analyzer.getExpressionTypes();
        Map<NodeRef<Expression>, Type> expressionCoercions = analyzer.getExpressionCoercions();
        Set<NodeRef<Expression>> typeOnlyCoercions = analyzer.getTypeOnlyCoercions();
        Map<NodeRef<Expression>, Type> sortKeyCoercionsForFrameBoundCalculation = analyzer.getSortKeyCoercionsForFrameBoundCalculation();
        Map<NodeRef<Expression>, Type> sortKeyCoercionsForFrameBoundComparison = analyzer.getSortKeyCoercionsForFrameBoundComparison();
        Map<NodeRef<Expression>, FunctionHandle> frameBoundCalculations = analyzer.getFrameBoundCalculations();
        Map<NodeRef<FunctionCall>, FunctionHandle> resolvedFunctions = analyzer.getResolvedFunctions();

        analysis.addTypes(expressionTypes);
        analysis.addCoercions(expressionCoercions, typeOnlyCoercions, sortKeyCoercionsForFrameBoundCalculation, sortKeyCoercionsForFrameBoundComparison);
        analysis.addFrameBoundCalculations(frameBoundCalculations);
        analysis.addFunctionHandles(resolvedFunctions);
        analysis.addColumnReferences(analyzer.getColumnReferences());
        analysis.addLambdaArgumentReferences(analyzer.getLambdaArgumentReferences());
        analysis.addTableColumnAndSubfieldReferences(
                accessControl,
                session.getIdentity(),
                session.getTransactionId(),
                session.getAccessControlContext(),
                analyzer.getTableColumnAndSubfieldReferences(),
                analyzer.getTableColumnAndSubfieldReferencesForAccessControl());

        return new ExpressionAnalysis(
                expressionTypes,
                expressionCoercions,
                analyzer.getSubqueryInPredicates(),
                analyzer.getScalarSubqueries(),
                analyzer.getExistsSubqueries(),
                analyzer.getColumnReferences(),
                analyzer.getTypeOnlyCoercions(),
                analyzer.getQuantifiedComparisons(),
                analyzer.getLambdaArgumentReferences(),
                analyzer.getWindowFunctions());
    }

    public static ExpressionAnalysis analyzeSqlFunctionExpression(
            FunctionAndTypeResolver functionAndTypeResolver,
            SqlFunctionProperties sqlFunctionProperties,
            Expression expression,
            Map<String, Type> argumentTypes)
    {
        ExpressionAnalyzer analyzer = ExpressionAnalyzer.createWithoutSubqueries(
                functionAndTypeResolver,
                Optional.empty(), // SQL function expression cannot contain session functions
                Optional.empty(),
                sqlFunctionProperties,
                TypeProvider.copyOf(argumentTypes),
                emptyMap(),
                node -> new SemanticException(NOT_SUPPORTED, node, "SQL function does not support subquery"),
                WarningCollector.NOOP,
                false);

        analyzer.analyze(
                expression,
                Scope.builder()
                        .withRelationType(
                                RelationId.anonymous(),
                                new RelationType(argumentTypes.entrySet().stream()
                                        .map(entry -> Field.newUnqualified(expression.getLocation(), entry.getKey(), entry.getValue()))
                                        .collect(toImmutableList()))).build());
        return new ExpressionAnalysis(
                analyzer.getExpressionTypes(),
                analyzer.getExpressionCoercions(),
                analyzer.getSubqueryInPredicates(),
                analyzer.getScalarSubqueries(),
                analyzer.getExistsSubqueries(),
                analyzer.getColumnReferences(),
                analyzer.getTypeOnlyCoercions(),
                analyzer.getQuantifiedComparisons(),
                analyzer.getLambdaArgumentReferences(),
                analyzer.getWindowFunctions());
    }

    private static ExpressionAnalyzer create(
            Analysis analysis,
            Session session,
            Metadata metadata,
            SqlParser sqlParser,
            AccessControl accessControl,
            TypeProvider types,
            WarningCollector warningCollector)
    {
        return new ExpressionAnalyzer(
                metadata.getFunctionAndTypeManager().getFunctionAndTypeResolver(),
                node -> new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector),
                Optional.of(session.getSessionFunctions()),
                session.getTransactionId(),
                session.getSqlFunctionProperties(),
                types,
                analysis.getParameters(),
                warningCollector,
                analysis.isDescribe(),
                ImmutableMap.of());
    }

    private static ExpressionAnalyzer create(
            Analysis analysis,
            Session session,
            Metadata metadata,
            SqlParser sqlParser,
            AccessControl accessControl,
            TypeProvider types,
            WarningCollector warningCollector,
            Map<NodeRef<Expression>, Type> outerScopeSymbolTypes)
    {
        return new ExpressionAnalyzer(
                metadata.getFunctionAndTypeManager().getFunctionAndTypeResolver(),
                node -> new StatementAnalyzer(analysis, metadata, sqlParser, accessControl, session, warningCollector),
                Optional.of(session.getSessionFunctions()),
                session.getTransactionId(),
                session.getSqlFunctionProperties(),
                types,
                analysis.getParameters(),
                warningCollector,
                analysis.isDescribe(),
                outerScopeSymbolTypes);
    }

    public static ExpressionAnalyzer createConstantAnalyzer(FunctionAndTypeResolver functionAndTypeResolver, Session session, Map<NodeRef<Parameter>, Expression> parameters, WarningCollector warningCollector)
    {
        return createWithoutSubqueries(
                functionAndTypeResolver,
                session,
                parameters,
                EXPRESSION_NOT_CONSTANT,
                "Constant expression cannot contain a subquery",
                warningCollector,
                false);
    }

    public static ExpressionAnalyzer createConstantAnalyzer(Metadata metadata, Session session, Map<NodeRef<Parameter>, Expression> parameters, WarningCollector warningCollector, boolean isDescribe)
    {
        return createWithoutSubqueries(
                metadata.getFunctionAndTypeManager().getFunctionAndTypeResolver(),
                session,
                parameters,
                EXPRESSION_NOT_CONSTANT,
                "Constant expression cannot contain a subquery",
                warningCollector,
                isDescribe);
    }

    public static ExpressionAnalyzer createWithoutSubqueries(
            FunctionAndTypeResolver functionAndTypeResolver,
            Session session,
            Map<NodeRef<Parameter>, Expression> parameters,
            SemanticErrorCode errorCode,
            String message,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        return createWithoutSubqueries(
                functionAndTypeResolver,
                session,
                TypeProvider.empty(),
                parameters,
                node -> new SemanticException(errorCode, node, message),
                warningCollector,
                isDescribe);
    }

    public static ExpressionAnalyzer createWithoutSubqueries(
            FunctionAndTypeResolver functionAndTypeResolver,
            Session session,
            TypeProvider symbolTypes,
            Map<NodeRef<Parameter>, Expression> parameters,
            Function<? super Node, ? extends RuntimeException> statementAnalyzerRejection,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        return createWithoutSubqueries(
                functionAndTypeResolver,
                Optional.of(session.getSessionFunctions()),
                session.getTransactionId(),
                session.getSqlFunctionProperties(),
                symbolTypes,
                parameters,
                statementAnalyzerRejection,
                warningCollector,
                isDescribe);
    }

    public static ExpressionAnalyzer createWithoutSubqueries(
            FunctionAndTypeResolver functionAndTypeResolver,
            Optional<Map<SqlFunctionId, SqlInvokedFunction>> sessionFunctions,
            Optional<TransactionId> transactionId,
            SqlFunctionProperties sqlFunctionProperties,
            TypeProvider symbolTypes,
            Map<NodeRef<Parameter>, Expression> parameters,
            Function<? super Node, ? extends RuntimeException> statementAnalyzerRejection,
            WarningCollector warningCollector,
            boolean isDescribe)
    {
        return new ExpressionAnalyzer(
                functionAndTypeResolver,
                node -> {
                    throw statementAnalyzerRejection.apply(node);
                },
                sessionFunctions,
                transactionId,
                sqlFunctionProperties,
                symbolTypes,
                parameters,
                warningCollector,
                isDescribe,
                ImmutableMap.of());
    }

    public static boolean isNumericType(Type type)
    {
        return type.equals(BIGINT) ||
                type.equals(INTEGER) ||
                type.equals(SMALLINT) ||
                type.equals(TINYINT) ||
                type.equals(DOUBLE) ||
                type.equals(REAL) ||
                type instanceof DecimalType;
    }
}
