package it.unibz.inf.ontop.query.translation.path;

import it.unibz.inf.ontop.exception.OntopUnsupportedKGQueryException;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil;
import org.eclipse.rdf4j.query.algebra.And;
import org.eclipse.rdf4j.query.algebra.Compare;
import org.eclipse.rdf4j.query.algebra.Or;
import org.eclipse.rdf4j.query.algebra.ValueConstant;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;

import java.util.Map;

/**
 * Serializes a restricted SPARQL condition into a SQL fragment for per-level traversal pruning.
 *
 * Supported: comparisons, AND/OR, variables mapped to SQL columns, and literals.
 * Not supported: functions, arithmetic, variables not present in the mapping.
 */
public class SparqlConditionSqlSerializer {

    private final Map<String, String> variableToSql;

    public SparqlConditionSqlSerializer(Map<String, String> variableToSql) {
        this.variableToSql = variableToSql;
    }

    public String serialize(ValueExpr expression) throws OntopUnsupportedKGQueryException {
        if (expression instanceof Compare) {
            Compare compare = (Compare) expression;
            return serialize(compare.getLeftArg()) + " " + operator(compare.getOperator())
                    + " " + serialize(compare.getRightArg());
        }
        if (expression instanceof And) {
            And and = (And) expression;
            return "(" + serialize(and.getLeftArg()) + " AND " + serialize(and.getRightArg()) + ")";
        }
        if (expression instanceof Or) {
            Or or = (Or) expression;
            return "(" + serialize(or.getLeftArg()) + " OR " + serialize(or.getRightArg()) + ")";
        }
        if (expression instanceof Var) {
            String sql = variableToSql.get(((Var) expression).getName());
            if (sql == null)
                throw new OntopUnsupportedKGQueryException("Unsupported traversal variable: " + expression);
            return sql;
        }
        if (expression instanceof ValueConstant) {
            return literal(((ValueConstant) expression).getValue());
        }
        throw new OntopUnsupportedKGQueryException(
                "Unsupported traversal condition: " + expression.getClass().getSimpleName());
    }

    private String operator(Compare.CompareOp operator) throws OntopUnsupportedKGQueryException {
        switch (operator) {
            case EQ:
                return "=";
            case NE:
                return "<>";
            case LT:
                return "<";
            case LE:
                return "<=";
            case GT:
                return ">";
            case GE:
                return ">=";
            default:
                throw new OntopUnsupportedKGQueryException("Unsupported comparison: " + operator);
        }
    }

    private String literal(Value value) throws OntopUnsupportedKGQueryException {
        if (value instanceof Literal) {
            Literal literal = (Literal) value;
            if (XMLDatatypeUtil.isNumericDatatype(literal.getDatatype()))
                return literal.getLabel();
            return "'" + literal.getLabel().replace("'", "''") + "'";
        }
        throw new OntopUnsupportedKGQueryException("Unsupported traversal literal: " + value);
    }
}
