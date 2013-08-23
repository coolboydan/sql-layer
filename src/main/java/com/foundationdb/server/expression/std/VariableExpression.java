/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.expression.std;

import com.foundationdb.qp.exec.Plannable;
import com.foundationdb.qp.operator.QueryBindings;
import com.foundationdb.qp.operator.QueryContext;
import com.foundationdb.qp.row.Row;
import com.foundationdb.server.explain.CompoundExplainer;
import com.foundationdb.server.explain.ExplainContext;
import com.foundationdb.server.explain.Label;
import com.foundationdb.server.explain.PrimitiveExplainer;
import com.foundationdb.server.explain.Type;
import com.foundationdb.server.explain.std.ExpressionExplainer;
import com.foundationdb.server.expression.Expression;
import com.foundationdb.server.expression.ExpressionEvaluation;
import com.foundationdb.server.types.AkType;
import com.foundationdb.server.types.ValueSource;
import java.util.List;
import java.util.Map;

public final class VariableExpression implements Expression {

    // Expression interface

    @Override
    public boolean isConstant() {
        return false;
    }

    @Override
    public boolean needsBindings() {
        return true;
    }

    @Override
    public boolean needsRow() {
        return false;
    }

    @Override
    public ExpressionEvaluation evaluation() {
        return new InnerEvaluation(type, position);
    }

    @Override
    public AkType valueType() {
        return type;
    }

    public VariableExpression(AkType type, int position) {
        this.type = type;
        this.position = position;
    }

    // Object interface

    @Override
    public String toString() {
        return String.format("Variable(pos=%d)", position);
    }

    // object state

    private final AkType type;
    private final int position;

    @Override
    public String name()
    {
        return "Variable";
    }

    @Override
    public CompoundExplainer getExplainer(ExplainContext context)
    {
        CompoundExplainer ex = new ExpressionExplainer(Type.VARIABLE, name(), context);
        ex.addAttribute(Label.BINDING_POSITION, PrimitiveExplainer.getInstance(position));
        return ex;
    }

    public boolean nullIsContaminating()
    {
        return true;
    }

    private static class InnerEvaluation extends ExpressionEvaluation.Base {

        @Override
        public void of(Row row) {
        }

        @Override
        public void of(QueryContext context) {
        }

        @Override
        public void of(QueryBindings bindings) {
            this.bindings = bindings;
        }

        @Override
        public ValueSource eval() {
            return bindings.getValue(position);
        }

        @Override
        public void acquire() {
            ++ownedBy;
        }

        @Override
        public boolean isShared() {
            return ownedBy > 1;
        }

        @Override
        public void release() {
            assert ownedBy > 0 : ownedBy;
            --ownedBy;
        }

        private InnerEvaluation(AkType type, int position) {
            this.type = type;
            this.position = position;
        }

        private final AkType type;
        private final int position;
        private QueryBindings bindings;
        private int ownedBy = 0;
    }
}