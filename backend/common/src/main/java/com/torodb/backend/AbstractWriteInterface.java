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

package com.torodb.backend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

import com.google.common.base.Preconditions;
import com.torodb.backend.ErrorHandler.Context;
import com.torodb.core.backend.DidCursor;
import com.torodb.core.d2r.DocPartData;
import com.torodb.core.d2r.DocPartRow;
import com.torodb.core.transaction.metainf.FieldType;
import com.torodb.core.transaction.metainf.MetaCollection;
import com.torodb.core.transaction.metainf.MetaDocPart;
import com.torodb.core.transaction.metainf.MetaField;
import com.torodb.core.transaction.metainf.MetaScalar;
import com.torodb.kvdocument.values.KVValue;

/**
 *
 */
@Singleton
public abstract class AbstractWriteInterface implements WriteInterface {
    
    private final MetaDataReadInterface metaDataReadInterface;
    private final ErrorHandler errorHandler;
    private final SqlHelper sqlHelper;

    public AbstractWriteInterface(MetaDataReadInterface metaDataReadInterface,
            ErrorHandler errorHandler,
            SqlHelper sqlHelper) {
        super();
        this.metaDataReadInterface = metaDataReadInterface;
        this.errorHandler = errorHandler;
        this.sqlHelper = sqlHelper;
    }

    @Override
    public long deleteCollectionDocParts(@Nonnull DSLContext dsl,
            @Nonnull String schemaName, @Nonnull MetaCollection metaCollection,
            @Nonnull DidCursor didCursor
    ) {
        Iterator<? extends MetaDocPart> iterator = metaCollection.streamContainedMetaDocParts()
                .sorted(TableRefComparator.MetaDocPart.DESC).iterator();
        Connection c = dsl.configuration().connectionProvider().acquire();
        try{
            int maxBatchSize = 100;
            long updated = 0;
            
            while (didCursor.hasNext()) {
                Collection<Integer> dids = didCursor.getNextBatch(maxBatchSize);
    	        while (iterator.hasNext()){
    	        	MetaDocPart metaDocPart = iterator.next();
            	    String statement = getDeleteDocPartsStatement(schemaName, metaDocPart.getIdentifier(), dids);
            	    sqlHelper.executeUpdate(c, statement, Context.DELETE);
    	        }
    	        updated += dids.size();
            }
	        
	        return updated;
        }finally {
        	dsl.configuration().connectionProvider().release(c);
        }
    }

    protected abstract String getDeleteDocPartsStatement(String schemaName, String tableName, Collection<Integer> dids);

    @Override
    public void insertDocPartData(DSLContext dsl, String schemaName, DocPartData docPartData) {
        Iterator<DocPartRow> docPartRowIterator = docPartData.iterator();
        if (!docPartRowIterator.hasNext()) {
            return;
        }
        
        try {
            MetaDocPart metaDocPart = docPartData.getMetaDocPart();
            Iterator<MetaScalar> metaScalarIterator = docPartData.orderedMetaScalarIterator();
            Iterator<MetaField> metaFieldIterator = docPartData.orderedMetaFieldIterator();
            standardInsertDocPartData(dsl, schemaName, docPartData, metaDocPart, metaScalarIterator, metaFieldIterator, docPartRowIterator);
        } catch (DataAccessException ex) {
            throw errorHandler.handleException(Context.INSERT, ex);
        }
    }

    protected int getMaxBatchSize() {
        return 30;
    }
    
    protected void standardInsertDocPartData(DSLContext dsl, String schemaName, DocPartData docPartData, MetaDocPart metaDocPart, 
            Iterator<MetaScalar> metaScalarIterator, Iterator<MetaField> metaFieldIterator, 
            Iterator<DocPartRow> docPartRowIterator) {
        final int maxBatchSize = getMaxBatchSize();
        Collection<InternalField<?>> internalFields = metaDataReadInterface.getInternalFields(metaDocPart);
        List<FieldType> fieldTypeList = new ArrayList<>();
        String statement = getInsertDocPartDataStatement(schemaName, metaDocPart, metaFieldIterator, metaScalarIterator,
                internalFields, fieldTypeList);
        assert assertFieldTypeListIsConsistent(docPartData, fieldTypeList) : "fieldTypeList should be an ordered list of FieldType"
                + " from MetaScalar and MetaField following the the ordering of DocPartData.orderedMetaScalarIterator and"
                + " DocPartData.orderedMetaFieldIterator";
        
        Connection connection = dsl.configuration().connectionProvider().acquire();
        try {
            try (PreparedStatement preparedStatement = connection.prepareStatement(statement)) {
                int docCounter = 0;
                while (docPartRowIterator.hasNext()) {
                    DocPartRow docPartRow = docPartRowIterator.next();
                    docCounter++;
                    int parameterIndex = 1;
                    for (InternalField<?> internalField : internalFields) {
                        internalField.set(preparedStatement, parameterIndex, docPartRow);
                        parameterIndex++;
                    }
                    Iterator<FieldType> fieldTypeIterator = fieldTypeList.iterator();
                    for (KVValue<?> value : docPartRow.getScalarValues()) {
                        sqlHelper.setPreparedStatementNullableValue(
                                preparedStatement, parameterIndex++, 
                                fieldTypeIterator.next(),
                                value);
                    }
                    for (KVValue<?> value : docPartRow.getFieldValues()) {
                        sqlHelper.setPreparedStatementNullableValue(
                                preparedStatement, parameterIndex++, 
                                fieldTypeIterator.next(),
                                value);
                    }
                    preparedStatement.addBatch();
                    if (docCounter % maxBatchSize == 0 || !docPartRowIterator.hasNext()) {
                        preparedStatement.executeBatch();
                    }
                }
            }
        } catch(SQLException ex) {
            throw errorHandler.handleException(Context.INSERT, ex);
        } finally {
            dsl.configuration().connectionProvider().release(connection);
        }
    }

    protected abstract String getInsertDocPartDataStatement(String schemaName, MetaDocPart metaDocPart,
            Iterator<MetaField> metaFieldIterator, Iterator<MetaScalar> metaScalarIterator,
            Collection<InternalField<?>> internalFields, List<FieldType> fieldTypeList);
    
    private boolean assertFieldTypeListIsConsistent(DocPartData docPartData, List<FieldType> fieldTypeList) {
        Iterator<MetaScalar> metaScalarIterator = docPartData.orderedMetaScalarIterator();
        Iterator<MetaField> metaFieldIterator = docPartData.orderedMetaFieldIterator();
        Iterator<FieldType> fieldTypeIterator = fieldTypeList.iterator();
        while (metaScalarIterator.hasNext()) {
            if (!fieldTypeIterator.hasNext() || 
                    !metaScalarIterator.next().getType().equals(
                            fieldTypeIterator.next())) {
                return false;
            }
        }
        while (metaFieldIterator.hasNext()) {
            if (!fieldTypeIterator.hasNext() || 
                    !metaFieldIterator.next().getType().equals(
                            fieldTypeIterator.next())) {
                return false;
            }
        }
        return true;
    }
}
