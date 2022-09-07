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
package io.seata.rm.datasource.exec;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import io.seata.common.exception.ShouldNeverHappenException;
import io.seata.common.util.IOUtil;
import io.seata.common.util.StringUtils;
import io.seata.config.Configuration;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.common.DefaultValues;
import io.seata.rm.datasource.ColumnUtils;
import io.seata.rm.datasource.SqlGenerateUtils;
import io.seata.rm.datasource.StatementProxy;
import io.seata.rm.datasource.sql.struct.TableMeta;
import io.seata.rm.datasource.sql.struct.TableRecords;
import io.seata.sqlparser.SQLRecognizer;
import io.seata.sqlparser.SQLUpdateRecognizer;

/**
 * The type Update executor.
 *
 * @param <T> the type parameter
 * @param <S> the type parameter
 * @author sharajava
 */
public class UpdateExecutor<T, S extends Statement> extends AbstractDMLBaseExecutor<T, S> {

    private static final Configuration CONFIG = ConfigurationFactory.getInstance();

    private static final boolean ONLY_CARE_UPDATE_COLUMNS = CONFIG.getBoolean(
            ConfigurationKeys.TRANSACTION_UNDO_ONLY_CARE_UPDATE_COLUMNS, DefaultValues.DEFAULT_ONLY_CARE_UPDATE_COLUMNS);

    private Map<String, TableRecords> beforeImagesMap = new LinkedHashMap<>(4);
    private Map<String, TableRecords> afterImagesMap = new LinkedHashMap<>(4);

    /**
     * Instantiates a new Update executor.
     *
     * @param statementProxy    the statement proxy
     * @param statementCallback the statement callback
     * @param sqlRecognizer     the sql recognizer
     */
    public UpdateExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback,
                          SQLRecognizer sqlRecognizer) {
        super(statementProxy, statementCallback, sqlRecognizer);
    }

    @Override
    protected TableRecords beforeImage() throws SQLException {
        ArrayList<List<Object>> paramAppenderList = new ArrayList<>();
        SQLUpdateRecognizer recognizer = (SQLUpdateRecognizer) sqlRecognizer;
        String tableNames = recognizer.getTableName();
        if (StringUtils.isEmpty(tableNames)) {
            return  null;
        }
        String[] tableItems = tableNames.split(recognizer.MULTI_TABLE_NAME_SEPERATOR);
        //if tableItems.length == 1,it is single table update,like update t1 set name = ?; tableItems = {"t1"}
        //else it is update join sql,like update t1 inner join t2 on t1.id = t2.id set t1.name = ?; tableItems = {"update t1 inner join t2","t1","t2"}
        int itemTableIndex = tableItems.length == 1 ? 0 : 1;
        String unionTable = tableItems[0];
        for (int i = itemTableIndex; i < tableItems.length; i++) {
            String selectSQL = buildBeforeImageSQL(unionTable, tableItems[i], paramAppenderList);
            TableRecords tableRecords = buildTableRecords(getTableMeta(tableItems[i]), selectSQL, paramAppenderList);
            beforeImagesMap.put(tableItems[i], tableRecords);
        }
        return beforeImagesMap.get(unionTable);
    }

    private String buildBeforeImageSQL(String unionTable, String itemTable, ArrayList<List<Object>> paramAppenderList) {
        SQLUpdateRecognizer recognizer = (SQLUpdateRecognizer) sqlRecognizer;
        TableMeta itemTableMeta = this.getTableMeta(itemTable);
        List<String> updateColumns = recognizer.getUpdateColumns();
        List<String> itemTableUpdateColumns = getItemUpdateColumns(itemTableMeta.getAllColumns().keySet(), updateColumns);
        StringBuilder prefix = new StringBuilder("SELECT ");
        StringBuilder suffix = new StringBuilder(" FROM ").append(unionTable);
        String whereCondition = buildWhereCondition(recognizer, paramAppenderList);
        String orderByCondition = buildOrderCondition(recognizer, paramAppenderList);
        String limitCondition = buildLimitCondition(recognizer, paramAppenderList);
        if (StringUtils.isNotBlank(whereCondition)) {
            suffix.append(WHERE).append(whereCondition);
        }
        if (StringUtils.isNotBlank(orderByCondition)) {
            suffix.append(" ").append(orderByCondition);
        }
        if (StringUtils.isNotBlank(limitCondition)) {
            suffix.append(" ").append(limitCondition);
        }
        suffix.append(" FOR UPDATE");
        StringJoiner selectSQLJoin = new StringJoiner(", ", prefix.toString(), suffix.toString());
        if (ONLY_CARE_UPDATE_COLUMNS) {
            if (!containsPK(itemTable, itemTableUpdateColumns)) {
                selectSQLJoin.add(getColumnNamesInSQL(recognizer.getTableAlias(itemTable), itemTableMeta.getEscapePkNameList(getDbType())));
            }
            for (String columnName : itemTableUpdateColumns) {
                selectSQLJoin.add(columnName);
            }
            // The on update xxx columns will be auto update by db, so it's also the actually updated columns
            List<String> onUpdateColumns = itemTableMeta.getOnUpdateColumnsOnlyName();
            onUpdateColumns.removeAll(itemTableUpdateColumns);
            for (String onUpdateColumn : onUpdateColumns) {
                selectSQLJoin.add(ColumnUtils.addEscape(onUpdateColumn, getDbType()));
            }
        } else {
            for (String columnName : itemTableMeta.getAllColumns().keySet()) {
                selectSQLJoin.add(ColumnUtils.addEscape(columnName, getDbType()));
            }
        }
        return selectSQLJoin.toString();
    }

    private List<String> getItemUpdateColumns(Set<String> itemAllColumns, List<String> updateColumns) {
        List<String> itemUpdateColumns = new ArrayList<>();
        for (String updateColumn : updateColumns) {
            if (itemAllColumns.contains(updateColumn)) {
                itemUpdateColumns.add(updateColumn);
            }
        }
        return itemUpdateColumns;
    }


    @Override
    protected TableRecords afterImage(TableRecords beforeImage) throws SQLException {
        SQLUpdateRecognizer recognizer = (SQLUpdateRecognizer) sqlRecognizer;
        String tableNames = recognizer.getTableName();
        if (StringUtils.isEmpty(tableNames)) {
            return null;
        }
        String[] tableItems = tableNames.split(recognizer.MULTI_TABLE_NAME_SEPERATOR);
        int itemTableIndex = tableItems.length == 1 ? 0 : 1; // if length > 1,consider update join sql
        String unionTable = tableItems[0];
        for (int i = itemTableIndex; i < tableItems.length; i++) {
            //consider MultiUpdateExecutor regenerates updateExecutor object every time when doing afterImage
            TableRecords tableBeforeImage = beforeImagesMap.get(tableItems[i]) == null ? beforeImage : beforeImagesMap.get(tableItems[i]);
            String selectSQL = buildAfterImageSQL(unionTable, tableItems[i], tableBeforeImage);
            ResultSet rs = null;
            try (PreparedStatement pst = statementProxy.getConnection().prepareStatement(selectSQL)) {
                SqlGenerateUtils.setParamForPk(tableBeforeImage.pkRows(), getTableMeta(tableItems[i]).getPrimaryKeyOnlyName(), pst);
                rs = pst.executeQuery();
                TableRecords afterImage = TableRecords.buildRecords(getTableMeta(tableItems[i]), rs);
                afterImagesMap.put(tableItems[i], afterImage);
            } finally {
                IOUtil.close(rs);
            }
        }
        return afterImagesMap.get(unionTable);
    }

    @Override
    protected void prepareUndoLog(TableRecords beforeImage, TableRecords afterImage) throws SQLException {
        if (beforeImagesMap == null || afterImagesMap == null) {
            throw new IllegalStateException("images can not be null");
        }
        for (Map.Entry<String, TableRecords> entry : beforeImagesMap.entrySet()) {
            String tableName = entry.getKey();
            TableRecords tableBeforeImage = entry.getValue();
            TableRecords tableAfterImage = afterImagesMap.get(tableName);
            if (tableBeforeImage.getRows().size() != tableAfterImage.getRows().size()) {
                throw new ShouldNeverHappenException("Before image size is not equaled to after image size, probably because you updated the primary keys.");
            }
            super.prepareUndoLog(tableBeforeImage, tableAfterImage);
        }
    }

    private String buildAfterImageSQL(String unionTable, String itemTable, TableRecords beforeImage) throws SQLException {
        TableMeta itemTableMeta = getTableMeta(itemTable);
        StringBuilder prefix = new StringBuilder("SELECT ");
        String whereSql = SqlGenerateUtils.buildWhereConditionByPKs(itemTableMeta.getPrimaryKeyOnlyName(), beforeImage.pkRows().size(), getDbType());
        String suffix = " FROM " + unionTable + " WHERE " + whereSql;
        StringJoiner selectSQLJoiner = new StringJoiner(", ", prefix.toString(), suffix);
        if (ONLY_CARE_UPDATE_COLUMNS) {
            SQLUpdateRecognizer recognizer = (SQLUpdateRecognizer) sqlRecognizer;
            List<String> updateColumns = recognizer.getUpdateColumns();
            List<String> itemTableUpdateColumns = getItemUpdateColumns(itemTableMeta.getAllColumns().keySet(), updateColumns);
            if (!containsPK(itemTable, itemTableUpdateColumns)) {
                selectSQLJoiner.add(getColumnNamesInSQL(recognizer.getTableAlias(itemTable), itemTableMeta.getEscapePkNameList(getDbType())));
            }
            for (String columnName : itemTableUpdateColumns) {
                selectSQLJoiner.add(columnName);
            }

            // The on update xxx columns will be auto update by db, so it's also the actually updated columns
            List<String> onUpdateColumns = itemTableMeta.getOnUpdateColumnsOnlyName();
            onUpdateColumns.removeAll(itemTableUpdateColumns);
            for (String onUpdateColumn : onUpdateColumns) {
                selectSQLJoiner.add(ColumnUtils.addEscape(onUpdateColumn, getDbType()));
            }
        } else {
            for (String columnName : itemTableMeta.getAllColumns().keySet()) {
                selectSQLJoiner.add(ColumnUtils.addEscape(columnName, getDbType()));
            }
        }
        return selectSQLJoiner.toString();
    }

}
