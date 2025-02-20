/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.sqlite.mocks

import com.android.tools.idea.sqlite.databaseConnection.DatabaseConnection
import com.android.tools.idea.sqlite.databaseConnection.SqliteResultSet
import com.android.tools.idea.sqlite.model.SqliteSchema
import com.android.tools.idea.sqlite.model.SqliteStatement
import com.google.common.util.concurrent.ListenableFuture

class DatabaseConnectionWrapper(val databaseConnection: DatabaseConnection) : DatabaseConnection {

  var executedSqliteStatements = mutableListOf<String>()

  override fun close(): ListenableFuture<Unit> {
    return databaseConnection.close()
  }

  override fun readSchema(): ListenableFuture<SqliteSchema> {
    return databaseConnection.readSchema()
  }

  override fun query(sqliteStatement: SqliteStatement): ListenableFuture<SqliteResultSet> {
    executedSqliteStatements.add(sqliteStatement.sqliteStatementText)
    return databaseConnection.query(sqliteStatement)
  }

  override fun execute(sqliteStatement: SqliteStatement): ListenableFuture<Unit> {
    executedSqliteStatements.add(sqliteStatement.sqliteStatementText)
    return databaseConnection.execute(sqliteStatement)
  }
}
