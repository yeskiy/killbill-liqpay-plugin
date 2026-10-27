/*
 * Copyright 2025-2026 Yehor Brodskyi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sailkit.billing.plugin.liqpay.dao;

import javax.sql.DataSource;

import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test(groups = "database")
public class LiqPayDaoPostgresTest extends LiqPayDaoContract {

    private PostgreSQLContainer db;
    private PGSimpleDataSource dataSource;

    @Override
    @BeforeClass
    public void applyDdlTwice() throws Exception {
        db = new PostgreSQLContainer("postgres:18");
        db.start();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(db.getJdbcUrl());
        dataSource.setUser(db.getUsername());
        dataSource.setPassword(db.getPassword());
        super.applyDdlTwice();
    }

    @AfterClass(alwaysRun = true)
    public void stopDatabase() {
        if (db != null) {
            db.stop();
        }
    }

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }

    @Override
    protected String ddlResource() {
        return "ddl-postgresql.sql";
    }
}
