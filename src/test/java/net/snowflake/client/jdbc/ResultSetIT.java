/*
 * Copyright (c) 2012-2019 Snowflake Computing Inc. All right reserved.
 */
package net.snowflake.client.jdbc;

import net.snowflake.client.ConditionalIgnoreRule;
import net.snowflake.client.RunningOnTravisCI;
import net.snowflake.client.jdbc.telemetry.Telemetry;
import net.snowflake.client.jdbc.telemetry.TelemetryClient;
import net.snowflake.client.jdbc.telemetry.TelemetryData;
import net.snowflake.client.jdbc.telemetry.TelemetryField;
import net.snowflake.client.jdbc.telemetry.TelemetryUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Test ResultSet
 */
@RunWith(Parameterized.class)
public class ResultSetIT extends BaseJDBCTest
{
  @Parameterized.Parameters
  public static Object[][] data()
  {
    // all tests in this class need to run for both query result formats json and arrow
    if (BaseJDBCTest.isArrowTestsEnabled())
    {
      return new Object[][]{
          {"JSON"}
          , {"Arrow"}
      };
    }
    else
    {
      return new Object[][]{
          {"JSON"}
      };
    }
  }

  private static String queryResultFormat;

  public ResultSetIT(String queryResultFormat)
  {
    this.queryResultFormat = queryResultFormat;
  }

  private final String selectAllSQL = "select * from test_rs";

  public static Connection getConnection(int injectSocketTimeout)
  throws SQLException
  {
    Connection connection = BaseJDBCTest.getConnection(injectSocketTimeout);

    Statement statement = connection.createStatement();
    statement.execute(
        "alter session set " +
        "TIMEZONE='America/Los_Angeles'," +
        "TIMESTAMP_TYPE_MAPPING='TIMESTAMP_LTZ'," +
        "TIMESTAMP_OUTPUT_FORMAT='DY, DD MON YYYY HH24:MI:SS TZHTZM'," +
        "TIMESTAMP_TZ_OUTPUT_FORMAT='DY, DD MON YYYY HH24:MI:SS TZHTZM'," +
        "TIMESTAMP_LTZ_OUTPUT_FORMAT='DY, DD MON YYYY HH24:MI:SS TZHTZM'," +
        "TIMESTAMP_NTZ_OUTPUT_FORMAT='DY, DD MON YYYY HH24:MI:SS TZHTZM'");
    statement.close();
    return connection;
  }

  public static Connection getConnection()
  throws SQLException
  {
    Connection conn = getConnection(BaseJDBCTest.DONT_INJECT_SOCKET_TIMEOUT);
    if (isArrowTestsEnabled())
    {
      conn.createStatement().execute("alter session set query_result_format = '" + queryResultFormat + "'");
    }
    return conn;
  }

  public static Connection getConnection(Properties paramProperties)
  throws SQLException
  {
    Connection conn = getConnection(DONT_INJECT_SOCKET_TIMEOUT, paramProperties, false, false);
    if (isArrowTestsEnabled())
    {
      conn.createStatement().execute("alter session set query_result_format = '" + queryResultFormat + "'");
    }
    return conn;
  }

  @Before
  public void setUp() throws SQLException
  {
    Connection con = getConnection();

    // TEST_RS
    con.createStatement().execute("create or replace table test_rs (colA string)");
    con.createStatement().execute("insert into test_rs values('rowOne')");
    con.createStatement().execute("insert into test_rs values('rowTwo')");
    con.createStatement().execute("insert into test_rs values('rowThree')");

    // ORDERS_JDBC
    Statement statement = con.createStatement();
    statement.execute("create or replace table orders_jdbc" +
                      "(C1 STRING NOT NULL COMMENT 'JDBC', "
                      + "C2 STRING, C3 STRING, C4 STRING, C5 STRING, C6 STRING, "
                      + "C7 STRING, C8 STRING, C9 STRING) "
                      + "stage_file_format = (field_delimiter='|' "
                      + "error_on_column_count_mismatch=false)");
    // put files
    assertTrue("Failed to put a file",
               statement.execute(
                   "PUT file://" +
                   getFullPathFileInResource(TEST_DATA_FILE) + " @%orders_jdbc"));
    assertTrue("Failed to put a file",
               statement.execute(
                   "PUT file://" +
                   getFullPathFileInResource(TEST_DATA_FILE_2) + " @%orders_jdbc"));

    int numRows =
        statement.executeUpdate("copy into orders_jdbc");

    assertEquals("Unexpected number of rows copied: " + numRows, 73, numRows);


    con.close();
  }

  @After
  public void tearDown() throws SQLException
  {
    Connection con = getConnection();
    con.createStatement().execute("drop table if exists orders_jdbc");
    con.createStatement().execute("drop table if exists test_rs");
    con.close();
  }

  @Test
  public void testFindColumn() throws SQLException
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();
    ResultSet resultSet = statement.executeQuery(selectAllSQL);
    assertEquals(1, resultSet.findColumn("COLA"));
    statement.close();
    connection.close();
  }

  @Test
  public void testGetMethod() throws Throwable
  {
    String prepInsertString = "insert into test_get values(?, ?, ?, ?, ?, ?, ?, ?)";
    int bigInt = Integer.MAX_VALUE;
    long bigLong = Long.MAX_VALUE;
    short bigShort = Short.MAX_VALUE;
    String str = "hello";
    double bigDouble = Double.MAX_VALUE;
    float bigFloat = Float.MAX_VALUE;

    Connection connection = getConnection();
    Clob clob = connection.createClob();
    clob.setString(1, "hello world");
    Statement statement = connection.createStatement();
    statement.execute("create or replace table test_get(colA integer, colB number, colC number, "
                      + "colD string, colE double, colF float, colG boolean, colH text)");

    PreparedStatement prepStatement = connection.prepareStatement(prepInsertString);
    prepStatement.setInt(1, bigInt);
    prepStatement.setLong(2, bigLong);
    prepStatement.setLong(3, bigShort);
    prepStatement.setString(4, str);
    prepStatement.setDouble(5, bigDouble);
    prepStatement.setFloat(6, bigFloat);
    prepStatement.setBoolean(7, true);
    prepStatement.setClob(8, clob);
    prepStatement.execute();

    statement.execute("select * from test_get");
    ResultSet resultSet = statement.getResultSet();
    resultSet.next();
    assertEquals(bigInt, resultSet.getInt(1));
    assertEquals(bigInt, resultSet.getInt("COLA"));
    assertEquals(bigLong, resultSet.getLong(2));
    assertEquals(bigLong, resultSet.getLong("COLB"));
    assertEquals(bigShort, resultSet.getShort(3));
    assertEquals(bigShort, resultSet.getShort("COLC"));
    assertEquals(str, resultSet.getString(4));
    assertEquals(str, resultSet.getString("COLD"));
    Reader reader = resultSet.getCharacterStream("COLD");
    char[] sample = new char[str.length()];

    assertEquals(str.length(), reader.read(sample));
    assertEquals(str.charAt(0), sample[0]);
    assertEquals(str, new String(sample));

    //assertEquals(bigDouble, resultSet.getDouble(5), 0);
    //assertEquals(bigDouble, resultSet.getDouble("COLE"), 0);
    assertEquals(bigFloat, resultSet.getFloat(6), 0);
    assertEquals(bigFloat, resultSet.getFloat("COLF"), 0);
    assertTrue(resultSet.getBoolean(7));
    assertTrue(resultSet.getBoolean("COLG"));
    assertEquals("hello world", resultSet.getClob("COLH").toString());

    //test getStatement method
    assertEquals(statement, resultSet.getStatement());

    prepStatement.close();
    statement.execute("drop table if exists table_get");
    statement.close();
    resultSet.close();
    connection.close();
  }

  @Test
  public void testGetObjectOnDatabaseMetadataResultSet()
  throws SQLException
  {
    Connection connection = getConnection();
    DatabaseMetaData databaseMetaData = connection.getMetaData();
    ResultSet resultSet = databaseMetaData.getTypeInfo();
    resultSet.next();
    // SNOW-21375 "NULLABLE" Column is a SMALLINT TYPE
    assertEquals(DatabaseMetaData.typeNullable, resultSet.getObject("NULLABLE"));
    resultSet.close();
    connection.close();
  }

  @Test
  public void testGetBigDecimal() throws SQLException
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();
    statement.execute("create or replace table test_get(colA number(38,9))");
    PreparedStatement preparedStatement = connection.prepareStatement(
        "insert into test_get values(?)");
    BigDecimal bigDecimal1 = new BigDecimal("10000000000");
    preparedStatement.setBigDecimal(1, bigDecimal1);
    preparedStatement.executeUpdate();

    BigDecimal bigDecimal2 = new BigDecimal("100000000.123456789");
    preparedStatement.setBigDecimal(1, bigDecimal2);
    preparedStatement.execute();

    statement.execute("select * from test_get order by 1");
    ResultSet resultSet = statement.getResultSet();
    resultSet.next();
    assertEquals(bigDecimal2, resultSet.getBigDecimal(1));
    assertEquals(bigDecimal2, resultSet.getBigDecimal("COLA"));

    preparedStatement.close();
    statement.execute("drop table if exists test_get");
    statement.close();
    resultSet.close();
    connection.close();
  }

  @Test
  public void testCursorPosition() throws SQLException
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();
    statement.execute(selectAllSQL);
    ResultSet resultSet = statement.getResultSet();
    resultSet.next();
    assertTrue(resultSet.isFirst());
    assertEquals(1, resultSet.getRow());
    resultSet.next();
    assertTrue(!resultSet.isFirst());
    assertEquals(2, resultSet.getRow());
    assertTrue(!resultSet.isLast());
    resultSet.next();
    assertEquals(3, resultSet.getRow());
    assertTrue(resultSet.isLast());
    resultSet.next();
    assertTrue(resultSet.isAfterLast());
    statement.close();
    connection.close();
  }

  @Test
  public void testGetBytes() throws SQLException
  {
    Properties props = new Properties();
    props.setProperty("enable_binary_datatype", "true");
    Connection connection = getConnection(props);
    Statement statement = connection.createStatement();
    statement.execute("create or replace table bin (b Binary)");

    byte[] bytes1 = new byte[0];
    byte[] bytes2 = {(byte) 0xAB, (byte) 0xCD, (byte) 0x12};
    byte[] bytes3 = {(byte) 0x00, (byte) 0xFF, (byte) 0x42, (byte) 0x01};

    PreparedStatement prepStatement = connection.prepareStatement(
        "insert into bin values (?), (?), (?)");
    prepStatement.setBytes(1, bytes1);
    prepStatement.setBytes(2, bytes2);
    prepStatement.setBytes(3, bytes3);
    prepStatement.execute();

    // Get results in hex format (default).
    ResultSet resultSet = statement.executeQuery("select * from bin");
    resultSet.next();
    assertArrayEquals(bytes1, resultSet.getBytes(1));
    assertEquals("", resultSet.getString(1));
    resultSet.next();
    assertArrayEquals(bytes2, resultSet.getBytes(1));
    assertEquals("ABCD12", resultSet.getString(1));
    resultSet.next();
    assertArrayEquals(bytes3, resultSet.getBytes(1));
    assertEquals("00FF4201", resultSet.getString(1));

    // Get results in base64 format.
    props.setProperty("binary_output_format", "BAse64");
    connection = getConnection(props);
    statement = connection.createStatement();
    resultSet = statement.executeQuery("select * from bin");
    resultSet.next();
    assertArrayEquals(bytes1, resultSet.getBytes(1));
    assertEquals("", resultSet.getString(1));
    resultSet.next();
    assertArrayEquals(bytes2, resultSet.getBytes(1));
    assertEquals("q80S", resultSet.getString(1));
    resultSet.next();
    assertArrayEquals(bytes3, resultSet.getBytes(1));
    assertEquals("AP9CAQ==", resultSet.getString(1));

    statement.execute("drop table if exists bin");
    connection.close();
  }

  @Test
  public void testResultSetMetadata() throws SQLException
  {
    Connection connection = getConnection();
    final Map<String, String> params = getConnectionParameters();
    Statement statement = connection.createStatement();

    statement.execute("create or replace table test_rsmd(colA number(20, 5), colB string)");
    statement.execute("insert into test_rsmd values(1.00, 'str'),(2.00, 'str2')");

    ResultSet resultSet = statement.executeQuery("select * from test_rsmd");
    ResultSetMetaData resultSetMetaData = resultSet.getMetaData();

    assertEquals(params.get("database").toUpperCase(),
                 resultSetMetaData.getCatalogName(1).toUpperCase());
    assertEquals(params.get("schema").toUpperCase(),
                 resultSetMetaData.getSchemaName(1).toUpperCase());
    assertEquals("TEST_RSMD", resultSetMetaData.getTableName(1));
    assertEquals(String.class.getName(), resultSetMetaData.getColumnClassName(2));
    assertEquals(2, resultSetMetaData.getColumnCount());
    assertEquals(22, resultSetMetaData.getColumnDisplaySize(1));
    assertEquals("COLA", resultSetMetaData.getColumnLabel(1));
    assertEquals("COLA", resultSetMetaData.getColumnName(1));
    assertEquals(3, resultSetMetaData.getColumnType(1));
    assertEquals("NUMBER", resultSetMetaData.getColumnTypeName(1));
    assertEquals(20, resultSetMetaData.getPrecision(1));
    assertEquals(5, resultSetMetaData.getScale(1));
    assertFalse(resultSetMetaData.isAutoIncrement(1));
    assertFalse(resultSetMetaData.isCaseSensitive(1));
    assertFalse(resultSetMetaData.isCurrency(1));
    assertFalse(resultSetMetaData.isDefinitelyWritable(1));
    assertEquals(ResultSetMetaData.columnNullable, resultSetMetaData.isNullable(1));
    assertTrue(resultSetMetaData.isReadOnly(1));
    assertTrue(resultSetMetaData.isSearchable(1));
    assertTrue(resultSetMetaData.isSigned(1));

    statement.execute("drop table if exists test_rsmd");
    statement.close();
    connection.close();
  }

  // SNOW-31647
  @Test
  public void testColumnMetaWithZeroPrecision() throws SQLException
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();

    statement.execute("create or replace table testColDecimal(cola number(38, 0), " +
                      "colb number(17, 5))");

    ResultSet resultSet = statement.executeQuery("select * from testColDecimal");
    ResultSetMetaData resultSetMetaData = resultSet.getMetaData();

    assertThat(resultSetMetaData.getColumnType(1), is(Types.BIGINT));
    assertThat(resultSetMetaData.getColumnType(2), is(Types.DECIMAL));
    assertThat(resultSetMetaData.isSigned(1), is(true));
    assertThat(resultSetMetaData.isSigned(2), is(true));


    statement.execute("drop table if exists testColDecimal");

    connection.close();
  }

  @Test
  public void testGetObjectOnFixedView() throws Exception
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();

    statement.execute(
        "create or replace table testFixedView" +
        "(C1 STRING NOT NULL COMMENT 'JDBC', "
        + "C2 STRING, C3 STRING, C4 STRING, C5 STRING, C6 STRING, "
        + "C7 STRING, C8 STRING, C9 STRING) "
        + "stage_file_format = (field_delimiter='|' "
        + "error_on_column_count_mismatch=false)");

    // put files
    assertTrue("Failed to put a file",
               statement.execute(
                   "PUT file://" +
                   getFullPathFileInResource(TEST_DATA_FILE) + " @%testFixedView"));

    ResultSet resultSet = statement.executeQuery(
        "PUT file://" +
        getFullPathFileInResource(TEST_DATA_FILE_2) + " @%testFixedView");

    ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
    while (resultSet.next())
    {
      for (int i = 0; i < resultSetMetaData.getColumnCount(); i++)
      {
        assertNotNull(resultSet.getObject(i + 1));
      }
    }

    resultSet.close();
    statement.execute("drop table if exists testFixedView");
    statement.close();
    connection.close();
  }

  @Test
  public void testGetColumnDisplaySizeAndPrecision() throws SQLException
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();

    ResultSet resultSet = statement.executeQuery("select cast(1 as char)");
    ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
    assertEquals(1, resultSetMetaData.getColumnDisplaySize(1));
    assertEquals(1, resultSetMetaData.getPrecision(1));

    resultSet = statement.executeQuery("select cast(1 as number(38, 0))");
    resultSetMetaData = resultSet.getMetaData();
    assertEquals(39, resultSetMetaData.getColumnDisplaySize(1));
    assertEquals(38, resultSetMetaData.getPrecision(1));

    resultSet = statement.executeQuery("select cast(1 as decimal(25, 15))");
    resultSetMetaData = resultSet.getMetaData();
    assertEquals(27, resultSetMetaData.getColumnDisplaySize(1));
    assertEquals(25, resultSetMetaData.getPrecision(1));

    resultSet = statement.executeQuery("select cast(1 as string)");
    resultSetMetaData = resultSet.getMetaData();
    assertEquals(16777216, resultSetMetaData.getColumnDisplaySize(1));
    assertEquals(16777216, resultSetMetaData.getPrecision(1));

    resultSet = statement.executeQuery("select cast(1 as string(30))");
    resultSetMetaData = resultSet.getMetaData();
    assertEquals(30, resultSetMetaData.getColumnDisplaySize(1));
    assertEquals(30, resultSetMetaData.getPrecision(1));

    resultSet = statement.executeQuery("select to_date('2016-12-13', 'YYYY-MM-DD')");
    resultSetMetaData = resultSet.getMetaData();
    assertEquals(10, resultSetMetaData.getColumnDisplaySize(1));
    assertEquals(10, resultSetMetaData.getPrecision(1));

    resultSet = statement.executeQuery("select to_time('12:34:56', 'HH24:MI:SS')");
    resultSetMetaData = resultSet.getMetaData();
    assertEquals(8, resultSetMetaData.getColumnDisplaySize(1));
    assertEquals(8, resultSetMetaData.getPrecision(1));

    statement.close();
    connection.close();
  }

  @Test
  public void testGetBoolean() throws SQLException
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();
    statement.execute("create or replace table testBoolean(cola boolean)");
    statement.execute("insert into testBoolean values(false)");
    ResultSet resultSet = statement.executeQuery("select * from testBoolean");
    resultSet.next();
    assertFalse(resultSet.getBoolean(1));

    statement.execute("insert into testBoolean values(true)");
    resultSet = statement.executeQuery("select * from testBoolean");
    resultSet.next();
    assertFalse(resultSet.getBoolean(1));
    resultSet.next();
    assertTrue(resultSet.getBoolean(1));
    statement.execute("drop table if exists testBoolean");
    statement.close();
    connection.close();
  }

  @Test
  public void testGetClob() throws Throwable
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();
    statement.execute("create or replace table testClob(cola text)");
    statement.execute("insert into testClob values('hello world')");
    statement.execute("insert into testClob values('hello world1')");
    statement.execute("insert into testClob values('hello world2')");
    statement.execute("insert into testClob values('hello world3')");
    ResultSet resultSet = statement.executeQuery("select * from testClob");
    resultSet.next();
    // test reading Clob
    char[] chars = new char[100];
    Reader reader = resultSet.getClob(1).getCharacterStream();
    int charRead;
    charRead = reader.read(chars, 0, chars.length);
    assertEquals(charRead, 11);
    assertEquals("hello world", resultSet.getClob(1).toString());

    // test reading truncated clob
    resultSet.next();
    Clob clob = resultSet.getClob(1);
    assertEquals(clob.length(), 12);
    clob.truncate(5);
    reader = clob.getCharacterStream();

    charRead = reader.read(chars, 0, chars.length);
    assertEquals(charRead, 5);

    // read from input stream
    resultSet.next();
    final InputStream input = resultSet.getClob(1).getAsciiStream();

    Reader in = new InputStreamReader(input, StandardCharsets.UTF_8);
    charRead = in.read(chars, 0, chars.length);
    assertEquals(charRead, 12);

    statement.close();
    connection.close();
  }

  @Test
  public void testFetchOnClosedResultSet() throws SQLException
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();
    ResultSet resultSet = statement.executeQuery(selectAllSQL);
    assertTrue(!resultSet.isClosed());
    resultSet.close();
    assertTrue(resultSet.isClosed());
    assertFalse(resultSet.next());
  }

  @Test
  public void testReleaseDownloaderCurrentMemoryUsage() throws SQLException
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();
    final long initialMemoryUsage = SnowflakeChunkDownloader.getCurrentMemoryUsage();

    statement.executeQuery(
        "select current_date(), true,2345234, 2343.0, 'testrgint\\n\\t' from table(generator(rowcount=>200000))");

    assertThat("hold memory usage for the resultSet before close",
               SnowflakeChunkDownloader.getCurrentMemoryUsage() - initialMemoryUsage > 0);
    statement.close();
    assertThat("closing statement didn't release memory allocated for result",
               SnowflakeChunkDownloader.getCurrentMemoryUsage(), equalTo(initialMemoryUsage));
    connection.close();
  }

  @Test
  @ConditionalIgnoreRule.ConditionalIgnore(condition = RunningOnTravisCI.class)
  public void testResultColumnSearchCaseSensitiveOld() throws Exception
  {
    subTestResultColumnSearchCaseSensitive("JDBC_RS_COLUMN_CASE_INSENSITIVE");
  }

  @Test
  public void testResultColumnSearchCaseSensitive() throws Exception
  {
    subTestResultColumnSearchCaseSensitive("CLIENT_RESULT_COLUMN_CASE_INSENSITIVE");
  }

  private void subTestResultColumnSearchCaseSensitive(String parameterName) throws Exception
  {
    Properties prop = new Properties();
    prop.put("tracing", "FINEST");
    Connection connection = getConnection(prop);
    Statement statement = connection.createStatement();

    ResultSet resultSet = statement.executeQuery("select 1 AS TESTCOL");

    resultSet.next();
    assertEquals("1", resultSet.getString("TESTCOL"));
    assertEquals("1", resultSet.getString("TESTCOL"));
    try
    {
      resultSet.getString("testcol");
      fail();
    }
    catch (SQLException e)
    {
      assertEquals("Column not found: testcol", e.getMessage());
    }

    // try to do case-insensitive search
    statement.executeQuery(
        String.format("alter session set %s=true", parameterName));

    resultSet = statement.executeQuery("select 1 AS TESTCOL");
    resultSet.next();

    // get twice so that the code path can hit the place where
    // we use cached key pair (columnName, index)
    assertEquals("1", resultSet.getString("TESTCOL"));
    assertEquals("1", resultSet.getString("TESTCOL"));
    assertEquals("1", resultSet.getString("testcol"));
    assertEquals("1", resultSet.getString("testcol"));
  }

  @Test
  public void testInvalidColumnIndex() throws SQLException
  {
    Connection connection = getConnection();
    Statement statement = connection.createStatement();
    ResultSet resultSet = statement.executeQuery(selectAllSQL);

    resultSet.next();
    try
    {
      resultSet.getString(0);
      fail();
    }
    catch (SQLException e)
    {
      assertEquals(200032, e.getErrorCode());
    }
    try
    {
      resultSet.getString(2);
      fail();
    }
    catch (SQLException e)
    {
      assertEquals(200032, e.getErrorCode());
    }
    resultSet.close();
    statement.close();
    connection.close();
  }

  /**
   * SNOW-28882: wasNull was not set properly
   */
  @Test
  public void testWasNull() throws Exception
  {
    Connection con = getConnection();
    ResultSet ret = con.createStatement().executeQuery(
        "select cast(1/nullif(0,0) as double)," +
        "cast(1/nullif(0,0) as int), 100, " +
        "cast(1/nullif(0,0) as number(8,2))");
    ret.next();
    assertThat("Double value cannot be null",
               ret.getDouble(1), equalTo(0.0));
    assertThat("wasNull should be true", ret.wasNull());
    assertThat("Integer value cannot be null",
               ret.getInt(2), equalTo(0));
    assertThat("wasNull should be true", ret.wasNull());
    assertThat("Non null column",
               ret.getInt(3), equalTo(100));
    assertThat("wasNull should be false", !ret.wasNull());
    assertThat("BigDecimal value must be null",
               ret.getBigDecimal(4), nullValue());
    assertThat("wasNull should be true", ret.wasNull());
  }

  /**
   * SNOW-28390
   */
  @Test
  public void testParseInfAndNaNNumber() throws Exception
  {
    Connection con = getConnection();
    ResultSet ret = con.createStatement().executeQuery(
        "select to_double('inf'), to_double('-inf')");
    ret.next();
    assertThat("Positive Infinite Number",
               ret.getDouble(1), equalTo(Double.POSITIVE_INFINITY));
    assertThat("Negative Infinite Number",
               ret.getDouble(2), equalTo(Double.NEGATIVE_INFINITY));
    assertThat("Positive Infinite Number",
               ret.getFloat(1), equalTo(Float.POSITIVE_INFINITY));
    assertThat("Negative Infinite Number",
               ret.getFloat(2), equalTo(Float.NEGATIVE_INFINITY));

    ret = con.createStatement().executeQuery(
        "select to_double('nan')");
    ret.next();
    assertThat("Parse NaN",
               ret.getDouble(1), equalTo(Double.NaN));
    assertThat("Parse NaN",
               ret.getFloat(1), equalTo(Float.NaN));
  }

  /**
   * SNOW-33227
   */
  @Test
  public void testTreatDecimalAsInt() throws Exception
  {
    Connection con = getConnection();
    ResultSet ret = con.createStatement().executeQuery(
        "select 1");

    ResultSetMetaData metaData = ret.getMetaData();
    assertThat(metaData.getColumnType(1), equalTo(Types.BIGINT));

    con.createStatement().execute("alter session set jdbc_treat_decimal_as_int = false");

    ret = con.createStatement().executeQuery("select 1");
    metaData = ret.getMetaData();
    assertThat(metaData.getColumnType(1), equalTo(Types.DECIMAL));

    con.close();
  }

  @Test
  public void testIsLast() throws Exception
  {

    Connection con = getConnection();
    ResultSet ret = con.createStatement().executeQuery(
        "select * from orders_jdbc");
    assertTrue("should be before the first", ret.isBeforeFirst());
    assertFalse("should not be the first", ret.isFirst());

    ret.next();

    assertFalse("should not be before the first", ret.isBeforeFirst());
    assertTrue("should be the first", ret.isFirst());

    int cnt = 0;
    while (ret.next())
    {
      cnt++;
      if (cnt == 72)
      {
        assertTrue("should be the last", ret.isLast());
        assertFalse("should not be after the last", ret.isAfterLast());
      }
    }
    assertEquals(72, cnt);

    ret.next();

    assertFalse("should not be the last", ret.isLast());
    assertTrue("should be afterthe last", ret.isAfterLast());

    // PUT one file
    ret = con.createStatement().executeQuery(
        "PUT file://" +
        getFullPathFileInResource(TEST_DATA_FILE) + " @~");

    assertTrue("should be before the first", ret.isBeforeFirst());
    assertFalse("should not be the first", ret.isFirst());

    ret.next();

    assertFalse("should not be before the first", ret.isBeforeFirst());
    assertTrue("should be the first", ret.isFirst());

    assertTrue("should be the last", ret.isLast());
    assertFalse("should not be after the last", ret.isAfterLast());

    ret.next();

    assertFalse("should not be the last", ret.isLast());
    assertTrue("should be after the last", ret.isAfterLast());
  }

  @Test
  public void testMultipleChunks() throws SQLException, IOException
  {
    Connection con = getConnection();
    Statement statement = con.createStatement();

    // 10000 rows should be enough to force result into multiple chunks
    ResultSet resultSet =
        statement.executeQuery("select seq8(), randstr(1000, random()) from table(generator(rowcount => 10000))");
    int cnt = 0;
    while (resultSet.next())
    {
      ++cnt;
    }
    assertTrue(cnt >= 0);
    Telemetry telemetry = con.unwrap(SnowflakeConnectionV1.class).getSfSession().getTelemetryClient();
    LinkedList<TelemetryData> logs = ((TelemetryClient) telemetry).logBuffer();

    // there should be a log for each of the following fields
    TelemetryField[] expectedFields =
        {TelemetryField.TIME_CONSUME_FIRST_RESULT, TelemetryField.TIME_CONSUME_LAST_RESULT,
         TelemetryField.TIME_WAITING_FOR_CHUNKS, TelemetryField.TIME_DOWNLOADING_CHUNKS,
         TelemetryField.TIME_PARSING_CHUNKS};
    boolean[] succeeded = new boolean[expectedFields.length];

    for (int i = 0; i < expectedFields.length; i++)
    {
      succeeded[i] = false;
      for (TelemetryData log : logs)
      {
        if (log.getMessage().get(TelemetryUtil.TYPE).textValue().equals(expectedFields[i].field))
        {
          succeeded[i] = true;
          break;
        }
      }
    }

    for (int i = 0; i < expectedFields.length; i++)
    {
      assertThat(String.format("%s field not found in telemetry logs\n", expectedFields[i].field), succeeded[i]);
    }
    telemetry.sendBatch();
  }

  @Test
  public void testUpdateCountOnCopyCmd() throws Exception
  {
    Connection con = getConnection();
    Statement statement = con.createStatement();

    statement.execute("create or replace table testcopy(cola string)");

    // stage table has no file. Should return 0.
    int rowCount = statement.executeUpdate("copy into testcopy");
    assertThat(rowCount, is(0));

    // copy one file into table stage
    statement.execute("copy into @%testcopy from (select 'test_string')");
    rowCount = statement.executeUpdate("copy into testcopy");
    assertThat(rowCount, is(1));

    //cleanup
    statement.execute("drop table if exists testcopy");

    con.close();
  }
}
