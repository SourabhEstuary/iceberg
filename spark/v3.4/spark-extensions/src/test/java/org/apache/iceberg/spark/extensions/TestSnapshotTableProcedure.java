/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark.extensions;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.spark.sql.AnalysisException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestSnapshotTableProcedure extends SparkExtensionsTestBase {
  private static final String SOURCE_NAME = "spark_catalog.default.source";

  // Currently we can only Snapshot only out of the Spark Session Catalog

  public TestSnapshotTableProcedure(
      String catalogName, String implementation, Map<String, String> config) {
    super(catalogName, implementation, config);
  }

  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @After
  public void removeTables() {
    sql("DROP TABLE IF EXISTS %s", tableName);
    sql("DROP TABLE IF EXISTS %s PURGE", SOURCE_NAME);
  }

  @Test
  public void testSnapshot() throws IOException {
    String location = temp.newFolder().toString();
    sql(
        "CREATE TABLE %s (id bigint NOT NULL, data string) USING parquet LOCATION '%s'",
        SOURCE_NAME, location);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", SOURCE_NAME);
    Object result =
        scalarSql("CALL %s.system.snapshot('%s', '%s')", catalogName, SOURCE_NAME, tableName);

    Assert.assertEquals("Should have added one file", 1L, result);

    Table createdTable = validationCatalog.loadTable(tableIdent);
    String tableLocation = createdTable.location();
    Assert.assertNotEquals("Table should not have the original location", location, tableLocation);

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(1L, "a"), row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @Test
  public void testSnapshotWithProperties() throws IOException {
    String location = temp.newFolder().toString();
    sql(
        "CREATE TABLE %s (id bigint NOT NULL, data string) USING parquet LOCATION '%s'",
        SOURCE_NAME, location);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", SOURCE_NAME);
    Object result =
        scalarSql(
            "CALL %s.system.snapshot(source_table => '%s', table => '%s', properties => map('foo','bar'))",
            catalogName, SOURCE_NAME, tableName);

    Assert.assertEquals("Should have added one file", 1L, result);

    Table createdTable = validationCatalog.loadTable(tableIdent);

    String tableLocation = createdTable.location();
    Assert.assertNotEquals("Table should not have the original location", location, tableLocation);

    Map<String, String> props = createdTable.properties();
    Assert.assertEquals("Should have extra property set", "bar", props.get("foo"));

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(1L, "a"), row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @Test
  public void testSnapshotWithAlternateLocation() throws IOException {
    Assume.assumeTrue(
        "No Snapshoting with Alternate locations with Hadoop Catalogs",
        !catalogName.contains("hadoop"));
    String location = temp.newFolder().toString();
    String snapshotLocation = temp.newFolder().toString();
    sql(
        "CREATE TABLE %s (id bigint NOT NULL, data string) USING parquet LOCATION '%s'",
        SOURCE_NAME, location);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", SOURCE_NAME);
    Object[] result =
        sql(
                "CALL %s.system.snapshot(source_table => '%s', table => '%s', location => '%s')",
                catalogName, SOURCE_NAME, tableName, snapshotLocation)
            .get(0);

    Assert.assertEquals("Should have added one file", 1L, result[0]);

    String storageLocation = validationCatalog.loadTable(tableIdent).location();
    Assert.assertEquals(
        "Snapshot should be made at specified location", snapshotLocation, storageLocation);

    sql("INSERT INTO TABLE %s VALUES (1, 'a')", tableName);

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(1L, "a"), row(1L, "a")),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }

  @Test
  public void testDropTable() throws IOException {
    String location = temp.newFolder().toString();
    sql(
        "CREATE TABLE %s (id bigint NOT NULL, data string) USING parquet LOCATION '%s'",
        SOURCE_NAME, location);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", SOURCE_NAME);

    Object result =
        scalarSql("CALL %s.system.snapshot('%s', '%s')", catalogName, SOURCE_NAME, tableName);
    Assert.assertEquals("Should have added one file", 1L, result);

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM %s", tableName));

    sql("DROP TABLE %s", tableName);

    assertEquals(
        "Source table should be intact",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM %s", SOURCE_NAME));
  }

  @Test
  public void testSnapshotWithConflictingProps() throws IOException {
    String location = temp.newFolder().toString();
    sql(
        "CREATE TABLE %s (id bigint NOT NULL, data string) USING parquet LOCATION '%s'",
        SOURCE_NAME, location);
    sql("INSERT INTO TABLE %s VALUES (1, 'a')", SOURCE_NAME);

    Object result =
        scalarSql(
            "CALL %s.system.snapshot("
                + "source_table => '%s',"
                + "table => '%s',"
                + "properties => map('%s', 'true', 'snapshot', 'false'))",
            catalogName, SOURCE_NAME, tableName, TableProperties.GC_ENABLED);
    Assert.assertEquals("Should have added one file", 1L, result);

    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row(1L, "a")),
        sql("SELECT * FROM %s", tableName));

    Table table = validationCatalog.loadTable(tableIdent);
    Map<String, String> props = table.properties();
    Assert.assertEquals("Should override user value", "true", props.get("snapshot"));
    Assert.assertEquals(
        "Should override user value", "false", props.get(TableProperties.GC_ENABLED));
  }

  @Test
  public void testInvalidSnapshotsCases() throws IOException {
    String location = temp.newFolder().toString();
    sql(
        "CREATE TABLE %s (id bigint NOT NULL, data string) USING parquet LOCATION '%s'",
        SOURCE_NAME, location);

    assertThatThrownBy(() -> sql("CALL %s.system.snapshot('foo')", catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessage("Missing required parameters: [table]");

    assertThatThrownBy(
            () -> sql("CALL %s.system.snapshot('n', 't', map('foo', 'bar'))", catalogName))
        .isInstanceOf(AnalysisException.class)
        .hasMessageStartingWith("Wrong arg type for location");

    assertThatThrownBy(
            () ->
                sql(
                    "CALL %s.system.snapshot('%s', 'fable', 'loc', map(2, 1, 1))",
                    catalogName, SOURCE_NAME))
        .isInstanceOf(AnalysisException.class)
        .hasMessageContaining(
            "The `map` requires 2n (n > 0) parameters but the actual number is 3");

    assertThatThrownBy(() -> sql("CALL %s.system.snapshot('', 'dest')", catalogName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot handle an empty identifier for argument source_table");

    assertThatThrownBy(() -> sql("CALL %s.system.snapshot('src', '')", catalogName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot handle an empty identifier for argument table");
  }

  @Test
  public void testSnapshotPartitionedWithParallelism() throws IOException {
    String location = temp.newFolder().toString();
    sql(
        "CREATE TABLE %s (id bigint NOT NULL, data string) USING parquet PARTITIONED BY (id) LOCATION '%s'",
        SOURCE_NAME, location);
    sql("INSERT INTO TABLE %s (id, data) VALUES (1, 'a'), (2, 'b')", SOURCE_NAME);

    assertEquals(
        "Procedure output must match",
        ImmutableList.of(row(2L)),
        sql(
            "CALL %s.system.snapshot(source_table => '%s', table => '%s', parallelism => %d)",
            catalogName, SOURCE_NAME, tableName, 2));
    assertEquals(
        "Should have expected rows",
        ImmutableList.of(row("a", 1L), row("b", 2L)),
        sql("SELECT * FROM %s ORDER BY id", tableName));
  }
}
