/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.sqlparser.druid.oracle;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleMultiInsertStatement;
import io.seata.common.exception.NotSupportYetException;
import io.seata.common.loader.LoadLevel;
import io.seata.sqlparser.SQLRecognizer;
import io.seata.sqlparser.druid.SQLOperateRecognizerHolder;
import io.seata.sqlparser.util.JdbcConstants;

/**
 * The Type OracleOperateRecognizerHolder
 *
 * @author Zhibei Hao
 */
@LoadLevel(name = JdbcConstants.ORACLE)
public class OracleOperateRecognizerHolder implements SQLOperateRecognizerHolder {

    @Override
    public SQLRecognizer getDeleteRecognizer(String sql, SQLStatement ast) {
        return new OracleDeleteRecognizer(sql, ast);
    }

    @Override
    public SQLRecognizer getInsertRecognizer(String sql, SQLStatement ast) {
        return new OracleInsertRecognizer(sql, ast);
    }

    @Override
    public SQLRecognizer getUpdateRecognizer(String sql, SQLStatement ast) {
        return new OracleUpdateRecognizer(sql, ast);
    }

    @Override
    public SQLRecognizer getSelectForUpdateRecognizer(String sql, SQLStatement ast) {
        if (((SQLSelectStatement) ast).getSelect().getFirstQueryBlock().isForUpdate()) {
            return new OracleSelectForUpdateRecognizer(sql, ast);
        }
        return null;
    }

    @Override
    public List<SQLRecognizer> getMultiInsertStatement(String sql, SQLStatement ast) {
        List<SQLRecognizer> sqlRecognizers = new ArrayList<>();
        OracleMultiInsertStatement oracleAst = (OracleMultiInsertStatement)ast;
        List<OracleMultiInsertStatement.Entry> entries = oracleAst.getEntries();
        for (OracleMultiInsertStatement.Entry entryItem : entries) {
            if (entryItem instanceof OracleMultiInsertStatement.InsertIntoClause) {
                sqlRecognizers.add(new OracleMultiInsertItemRecognizer(sql,ast,entryItem));
            } else {
                throw new NotSupportYetException("not support the batch insert sql syntax with not a InsertIntoClause");
            }
        }
        return sqlRecognizers;
    }
}
