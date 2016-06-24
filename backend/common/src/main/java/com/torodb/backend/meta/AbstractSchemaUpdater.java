/*
 *     This file is part of ToroDB.
 *
 *     ToroDB is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ToroDB is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with ToroDB. If not, see <http://www.gnu.org/licenses/>.
 *
 *     Copyright (c) 2014, 8Kdata Technology
 *     
 */

package com.torodb.backend.meta;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.sql.SQLException;

import javax.inject.Singleton;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jooq.DSLContext;
import org.jooq.Meta;
import org.jooq.Schema;
import org.jooq.Table;

import com.google.common.io.CharStreams;
import com.torodb.backend.SqlHelper;
import com.torodb.backend.SqlInterface;
import com.torodb.backend.ErrorHandler.Context;
import com.torodb.backend.exceptions.InvalidDatabaseException;
import com.torodb.backend.tables.SemanticTable;
import com.torodb.core.exceptions.SystemException;

@Singleton
public abstract class AbstractSchemaUpdater implements SchemaUpdater {

    private static final Logger LOGGER = LogManager.getLogger(AbstractSchemaUpdater.class);

    @Override
    public void checkOrCreate(
            DSLContext dsl, 
            Meta jooqMeta, 
            SqlInterface sqlInterface, 
            SqlHelper sqlHelper
    ) throws SQLException, IOException, InvalidDatabaseException {
        Schema torodbSchema = null;
        for (Schema schema : jooqMeta.getSchemas()) {
            if (sqlInterface.isSameIdentifier(TorodbSchema.TORODB_SCHEMA, schema.getName())) {
                torodbSchema = schema;
                break;
            }
        }
        if (torodbSchema == null) {
            LOGGER.info("Schema '{}' not found. Creating it...", TorodbSchema.TORODB_SCHEMA);
            createSchema(dsl, sqlInterface, sqlHelper);
            LOGGER.info("Schema '{}' created", TorodbSchema.TORODB_SCHEMA);
        }
        else {
            LOGGER.info("Schema '{}' found. Checking it...", TorodbSchema.TORODB_SCHEMA);
            checkSchema(torodbSchema, sqlInterface);
            LOGGER.info("Schema '{}' checked", TorodbSchema.TORODB_SCHEMA);
        }
    }

    protected void createSchema(DSLContext dsl, SqlInterface sqlInterface, SqlHelper sqlHelper) throws SQLException, IOException {
        sqlInterface.createSchema(dsl, TorodbSchema.TORODB_SCHEMA);
        sqlInterface.createMetaDatabaseTable(dsl);
        sqlInterface.createMetaCollectionTable(dsl);
        sqlInterface.createMetaDocPartTable(dsl);
        sqlInterface.createMetaFieldTable(dsl);
        sqlInterface.createMetaScalarTable(dsl);
    }
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void checkSchema(Schema torodbSchema, SqlInterface sqlInterface) throws InvalidDatabaseException {
        SemanticTable<?>[] metaTables = new SemanticTable[] {
            sqlInterface.getMetaDatabaseTable(),
            sqlInterface.getMetaCollectionTable(),
            sqlInterface.getMetaDocPartTable(),
            sqlInterface.getMetaFieldTable(),
            sqlInterface.getMetaScalarTable()
        };
        for (SemanticTable metaTable : metaTables) {
            String metaTableName = metaTable.getName();
            boolean metaTableFound = false;
            for (Table<?> table : torodbSchema.getTables()) {
                if (sqlInterface.isSameIdentifier(table.getName(), metaTableName)) {
                    metaTable.checkSemanticallyEquals(table);
                    metaTableFound = true;
                    LOGGER.info(table + " found and check");
                }
            }
            if (!metaTableFound) {
                throw new InvalidDatabaseException("The schema '" + TorodbSchema.TORODB_SCHEMA + "'"
                        + " does not contain the expected meta table '" 
                        + metaTableName +"'");
            }
        }
    }

    protected void executeSql(
            DSLContext dsl, 
            String resourcePath,
            SqlHelper sqlHelper
    ) throws IOException, SQLException {
        InputStream resourceAsStream
                = getClass().getResourceAsStream(resourcePath);
        if (resourceAsStream == null) {
            throw new SystemException(
                    "Resource '" + resourcePath + "' does not exist"
            );
        }
        try {
            String statementAsString
                    = CharStreams.toString(
                            new BufferedReader(
                                    new InputStreamReader(
                                            resourceAsStream,
                                            Charset.forName("UTF-8"))));
            sqlHelper.executeStatement(dsl, statementAsString, Context.unknown);
        } finally {
            resourceAsStream.close();
        }
    }
}
