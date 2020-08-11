package org.apache.nifi.processors.ngsi.ngsi.backends.postgresql;

import java.sql.Statement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.HashMap;
import java.util.Properties;

public class PostgreSQLBackendImpl implements PostgreSQLBackend {

    private static final String DRIVER_NAME = "org.postgresql.Driver";
    private PostgreSQLDriver driver;
    private PostgreSQLCache cache = null;

    /**
     * Constructor.
     * @param postgresqlHost
     * @param postgresqlPort
     * @param postgresqlUsername
     * @param postgresqlPassword
     */
    public PostgreSQLBackendImpl(String postgresqlHost, String postgresqlPort,
            String postgresqlUsername, String postgresqlPassword) {
        cache = new PostgreSQLCache();
        System.out.println("PostgreSQL cache created succesfully");
       
        driver = new PostgreSQLDriver(postgresqlHost, postgresqlPort, postgresqlUsername,
                postgresqlPassword);
    } // PostgreSQLBackendImpl

    /**
     * Sets the PostgreSQLDriver driver. It is protected since it is only used by the tests.
     * @param driver The PostgreSQLDriver driver to be set.
     */
    protected void setDriver(PostgreSQLDriver driver) {
        this.driver = driver;
    } // setDriver

    protected PostgreSQLDriver getDriver() {
        return driver;
    } // getDriver

    /**
     * Creates a schema given its name, if not exists.
     * @param schemaName
     * @throws Exception
     */
    @Override
    public void createSchema(String schemaName) throws Exception {
        boolean schemaCached = cache.isSchemaInCache(schemaName);     
        
        if (!schemaCached) {
            Statement stmt = null;

            // get a connection to an empty database
            Connection con = driver.getConnection("");

            try {
                stmt = con.createStatement();
            } catch (SQLException e) {
                throw new Exception(e.getMessage());
            } // try catch

            try {
                String query = "CREATE SCHEMA IF NOT EXISTS " + schemaName;
                System.out.println("Executing SQL query '" + query + "'");
                stmt.executeUpdate(query);
            } catch (SQLException e) {
                throw new Exception(e.getMessage());
            } // try catch

            closePostgreSQLObjects(con, stmt);
            cache.persistSchemaInCache(schemaName);
        } // if
        
    } // createSchema

    /**
     * Creates a table, given its name, if not exists in the given schema.
     * @param schemaName
     * @param tableName
     * @param typedFieldNames
     * @throws Exception
     */
    @Override
    public void createTable(String schemaName, String tableName, String typedFieldNames) throws Exception {
        boolean tableInSchema = cache.isTableInCachedSchema(schemaName, tableName);
        
        if (!tableInSchema) {
            Statement stmt = null;

            // get a connection to the given schema
            Connection con = driver.getConnection(schemaName);

            try {
                stmt = con.createStatement();
            } catch (SQLException e) {
                throw new Exception(e.getMessage());
            } // try catch

            try {
                String query = "CREATE TABLE IF NOT EXISTS " + schemaName + "." + tableName + " " + typedFieldNames;
                System.out.println("Executing SQL query '" + query + "'");
                stmt.executeUpdate(query);
            } catch (SQLException e) {
                throw new Exception(e.getMessage());
            } // try catch

            closePostgreSQLObjects(con, stmt);
            cache.persistTableInCache(schemaName, tableName);
        } // if
        
    } // createTable

    @Override
    public void insertContextData(String schemaName, String tableName, String fieldNames, String fieldValues)
        throws Exception {
        Statement stmt = null;

        // get a connection to the given database
        Connection con = driver.getConnection(schemaName);

        try {
            stmt = con.createStatement();
        } catch (SQLException e) {
            throw new Exception( e.getMessage());
        } // try catch

        try {
            String query = "INSERT INTO " + schemaName + "." + tableName + " " + fieldNames + " VALUES " + fieldValues;
            System.out.println("Executing SQL query '" + query + "'");
            stmt.executeUpdate(query);
        } catch (SQLTimeoutException e) {
            throw new Exception(e.getMessage());
        } catch (SQLException e) {
            throw new Exception(e.getMessage());
        } // try catch
    } // insertContextData

    /**
     * Close all the PostgreSQL objects previously opened by createSchema and createTable.
     * @param con
     * @param stmt
     * @return True if the PostgreSQL objects have been closed, false otherwise.
     */
    private void closePostgreSQLObjects(Connection con, Statement stmt) throws Exception {
        if (con != null) {
            try {
                con.close();
            } catch (SQLException e) {
                throw new Exception(e.getMessage());
            } // try catch
        } // if

        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                throw new Exception(e.getMessage());
            } // try catch
        } // if
    } // closePostgreSQLObjects

    /**
     * Driver class.
     */
    protected class PostgreSQLDriver {

        private final HashMap<String, Connection> connections;
        private final String postgresqlHost;
        private final String postgresqlPort;
        private final String postgresqlUsername;
        private final String postgresqlPassword;

        /**
         * Constructor.
         * @param postgresqlHost
         * @param postgresqlPort
         * @param postgresqlUsername
         * @param postgresqlPassword
         */
        public PostgreSQLDriver(String postgresqlHost, String postgresqlPort, 
                String postgresqlUsername, String postgresqlPassword) {
            connections = new HashMap<>();
            this.postgresqlHost = postgresqlHost;
            this.postgresqlPort = postgresqlPort;
            this.postgresqlUsername = postgresqlUsername;
            this.postgresqlPassword = postgresqlPassword;
        } // PostgreSQLDriver

        /**
         * Gets a connection to the PostgreSQL server.
         * @param schemaName
         * @return
         * @throws Exception
         */
        public Connection getConnection(String schemaName) throws Exception {
            try {
                // FIXME: the number of cached connections should be limited to a certain number; with such a limit
                //        number, if a new connection is needed, the oldest one is closed
                Connection con = connections.get(schemaName);

                if (con == null || !con.isValid(0)) {
                    if (con != null) {
                        con.close();
                    } // if

                    con = createConnection();
                    connections.put(schemaName, con);
                } // if

                return con;
            }  catch (ClassNotFoundException e) {
                throw new Exception(e.getMessage());
            } catch (SQLException e) {
                throw new Exception(e.getMessage());
            } // try catch
        } // getConnection

        /**
         * Gets if a connection is created for the given schema. It is protected since it is only used in the tests.
         * @param schemaName
         * @return True if the connection exists, false otherwise
         */
        protected boolean isConnectionCreated(String schemaName) {
            return connections.containsKey(schemaName);
        } // isConnectionCreated

        /**
         * Gets the number of connections created.
         * @return The number of connections created
         */
        protected int numConnectionsCreated() {
            return connections.size();
        } // numConnectionsCreated

        /**
         * Creates a PostgreSQL connection.
         * @param schemaName
         * @return A PostgreSQL connection
         * @throws Exception
         */
        private Connection createConnection()
            throws Exception {
            // dynamically load the PostgreSQL JDBC driver
            Class.forName(DRIVER_NAME);

            // return a connection based on the PostgreSQL JDBC driver
            String url = "jdbc:postgresql://" + this.postgresqlHost + ":" + this.postgresqlPort
                    + "/";
            Properties props = new Properties();
            props.setProperty("user", this.postgresqlUsername);
            props.setProperty("password", this.postgresqlPassword);
            props.setProperty("sslmode", "disable");
            props.setProperty("charSet", "UTF-8");

            System.out.println("Connecting to " + url);
            return DriverManager.getConnection(url, props);
        } // createConnection
    } // PostgreSQLDriver
} // PostgreSQLBackendImpl