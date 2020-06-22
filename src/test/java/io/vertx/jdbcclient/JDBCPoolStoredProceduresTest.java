/*
 * Copyright (c) 2011-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.jdbcclient;

import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.RunTestOnContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import org.junit.*;
import org.junit.runner.RunWith;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@RunWith(VertxUnitRunner.class)
public class JDBCPoolStoredProceduresTest {

  private static final JDBCConnectOptions options = new JDBCConnectOptions()
    .setJdbcUrl("jdbc:hsqldb:mem:" + JDBCPoolStoredProceduresTest.class.getSimpleName() + "?shutdown=true");

  private static final List<String> SQL = new ArrayList<>();

  static {
    System.setProperty("textdb.allow_full_path", "true");
    System.setProperty("statement.separator", ";;");

    SQL.add("drop table if exists customers");
    SQL.add("create table customers(id integer generated by default as identity, firstname varchar(50), lastname varchar(50), added timestamp)");
    SQL.add("insert into customers(firstname, lastname) values ('John', 'Doe')");
    SQL.add("create procedure new_customer(firstname varchar(50), lastname varchar(50))\n" +
      "  modifies sql data\n" +
      "  insert into customers values (default, firstname, lastname, current_timestamp)");
    SQL.add("create procedure customer_lastname(IN firstname varchar(50), OUT lastname varchar(50))\n" +
      "  modifies sql data\n" +
      "  select lastname into lastname from customers where firstname = firstname");
    SQL.add("create function an_hour_before()\n" +
      "  returns timestamp\n" +
      "  return now() - 1 hour");
    SQL.add("create procedure times2(INOUT param INT)\n" +
      "  modifies sql data\n" +
      "  SET param = param * 2");
  }

  public static void resetDb() throws SQLException {
    Connection conn = DriverManager.getConnection(options.getJdbcUrl());
    for (String sql : SQL) {
      conn.createStatement().execute(sql);
    }
  }

  @Rule
  public RunTestOnContext rule = new RunTestOnContext();

  private JDBCPool pool;

  @Before
  public void before() throws SQLException {
    resetDb();
    pool = JDBCPool.pool(rule.vertx(), options, new PoolOptions().setMaxSize(1));
  }

  @After
  public void after(TestContext should) {
    pool.close(should.asyncAssertSuccess());
  }

  @Test
  public void testStoredProcedureIn(TestContext should) {
    final Async test = should.async();

    String sql = "{call new_customer(?, ?)}";

    pool
      .preparedQuery(sql)
      .execute(Tuple.of("Paulo", "Lopes"))
      .onFailure(should::fail)
      .onSuccess(rows -> {
        should.assertNotNull(rows);

        // verify that data was saved
        pool
          .query("SELECT * from customers")
          .execute()
          .onFailure(should::fail)
          .onSuccess(rows1 -> {
            should.assertNotNull(rows1);
            should.assertEquals(1, rows1.size());

            for (Row row : rows1) {
              should.assertNotNull(row.getInteger(0));
              should.assertEquals("Paulo", row.getString(1));
              should.assertEquals("Lopes", row.getString(2));
              should.assertNotNull(row.getOffsetDateTime(3));
            }
            test.complete();
          });
      });
  }

  @Test
  public void testStoredProcedureInOut(TestContext should) {
    final Async test = should.async();

    String sql = "{call customer_lastname(?, ?)}";

    pool
      .preparedQuery(sql)
      .execute(Tuple.of("John", SqlOutParam.OUT(JDBCType.VARCHAR)))
      .onFailure(should::fail)
      .onSuccess(rows -> {
        should.assertNotNull(rows);
        should.assertEquals(1, rows.size());
        test.complete();
      });
  }
}
