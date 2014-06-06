package liquibase.executor.jvm;

import liquibase.change.Change;
import liquibase.database.*;
import liquibase.database.core.OracleDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.executor.AbstractExecutor;
import liquibase.executor.Executor;
import liquibase.executor.QueryResult;
import liquibase.executor.UpdateResult;
import liquibase.logging.LogFactory;
import liquibase.logging.Logger;
import liquibase.sql.UnparsedSql;
import liquibase.sql.visitor.SqlVisitor;
import liquibase.statement.*;
import liquibase.statement.core.RawSqlStatement;
import liquibase.util.JdbcUtils;
import liquibase.util.StringUtils;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class to simplify execution of SqlStatements.  Based heavily on <a href="http://static.springframework.org/spring/docs/2.0.x/reference/jdbc.html">Spring's JdbcTemplate</a>.
 * <br><br>
 * <b>Note: This class is currently intended for Liquibase-internal use only and may change without notice in the future</b>
 */
@SuppressWarnings({"unchecked"})
public class JdbcExecutor extends AbstractExecutor implements Executor {

    private Logger log = LogFactory.getLogger();

    @Override
    public boolean updatesDatabase() {
        return true;
    }

    @Override
    public QueryResult query(SqlStatement sql) throws DatabaseException {
        return query(sql, null);
    }

    @Override
    public QueryResult query(SqlStatement sql, List<SqlVisitor> sqlVisitors) throws DatabaseException {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            String[] sqlToExecute = applyVisitors(sql, sqlVisitors);

            if (sqlToExecute.length != 1) {
                throw new DatabaseException("Can only query with statements that return one sql statement");
            }
            log.debug("Executing QUERY database command: "+sqlToExecute[0]);

            stmt = ((JdbcConnection) database.getConnection()).getUnderlyingConnection().createStatement();
            rs = stmt.executeQuery(sqlToExecute[0]);

            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<String, Object>();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                for (int i = 1; i <= columnCount; i++) {
                    String key = metaData.getColumnLabel(i).toUpperCase();
                    Object obj = JdbcUtils.getResultSetValue(rs, i);
                    row.put(key, obj);
                }
                rows.add(row);
            }

            return new QueryResult(rows);
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            JdbcUtils.closeStatement(stmt);
            JdbcUtils.closeResultSet(rs);
        }
    }

    public Object execute(StatementCallback action, List<SqlVisitor> sqlVisitors) throws DatabaseException {
        DatabaseConnection con = database.getConnection();
        Statement stmt = null;
        try {
            if (con instanceof OfflineConnection) {
                throw new DatabaseException("Cannot execute commands against an offline database");
            }
            stmt = ((JdbcConnection) con).getUnderlyingConnection().createStatement();
            Statement stmtToUse = stmt;

            return action.doInStatement(stmtToUse);
        }
        catch (SQLException ex) {
            // Release Connection early, to avoid potential connection pool deadlock
            // in the case when the exception translator hasn't been initialized yet.
            JdbcUtils.closeStatement(stmt);
            stmt = null;
            throw new DatabaseException("Error executing SQL " + StringUtils.join(applyVisitors(action.getStatement(), sqlVisitors), "; on "+ con.getURL())+": "+ex.getMessage(), ex);
        }
        finally {
            JdbcUtils.closeStatement(stmt);
        }
    }

    @Override
    public void execute(Change change) throws DatabaseException {
        execute(change, new ArrayList<SqlVisitor>());
    }

    @Override
    public void execute(Change change, List<SqlVisitor> sqlVisitors) throws DatabaseException {
        SqlStatement[] sqlStatements = change.generateStatements(database);
        if (sqlStatements != null) {
            for (SqlStatement statement : sqlStatements) {
                execute(statement, sqlVisitors);
            }
        }
    }

    public Object execute(CallableStatementCallback action, List<SqlVisitor> sqlVisitors) throws DatabaseException {
        DatabaseConnection con = database.getConnection();

        if (con instanceof OfflineConnection) {
            throw new DatabaseException("Cannot execute commands against an offline database");
        }

        CallableStatement stmt = null;
        try {
            String sql = applyVisitors(action.getStatement(), sqlVisitors)[0];

            stmt = ((JdbcConnection) con).getUnderlyingConnection().prepareCall(sql);
            return action.doInCallableStatement(stmt);
        }
        catch (SQLException ex) {
            // Release Connection early, to avoid potential connection pool deadlock
            // in the case when the exception translator hasn't been initialized yet.
            JdbcUtils.closeStatement(stmt);
            stmt = null;
            throw new DatabaseException("Error executing SQL " + StringUtils.join(applyVisitors(action.getStatement(), sqlVisitors), "; on "+ con.getURL())+": "+ex.getMessage(), ex);
        }
        finally {
            JdbcUtils.closeStatement(stmt);
        }
    }

    @Override
    public void execute(final SqlStatement sql) throws DatabaseException {
        execute(sql, new ArrayList<SqlVisitor>());
    }

    @Override
    public void execute(final SqlStatement sql, final List<SqlVisitor> sqlVisitors) throws DatabaseException {
        if(sql instanceof ExecutablePreparedStatement) {
            ((ExecutablePreparedStatement) sql).execute(new PreparedStatementFactory((JdbcConnection)database.getConnection()));
            return;
        }

        execute(new ExecuteStatementCallback(sql, sqlVisitors), sqlVisitors);
    }


    @Override
    public UpdateResult update(final SqlStatement sql) throws DatabaseException {
        return update(sql, null);
    }

    @Override
    public UpdateResult update(SqlStatement sql, List<SqlVisitor> sqlVisitors) throws DatabaseException {
        if (sql instanceof CallableSqlStatement) {
            throw new DatabaseException("Direct update using CallableSqlStatement not currently implemented");
        }

        Statement stmt = null;
        try {
            stmt = ((JdbcConnection) database.getConnection()).getUnderlyingConnection().createStatement();
            String[] sqlToExecute = applyVisitors(sql, sqlVisitors);
            if (sqlToExecute.length != 1) {
                throw new DatabaseException("Cannot call update on Statement that returns back multiple Sql objects");
            }
            log.debug("Executing UPDATE database command: "+sqlToExecute[0]);
            return new UpdateResult(stmt.executeUpdate(sqlToExecute[0]));
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } finally {
            JdbcUtils.closeStatement(stmt);
        }
    }

    @Override
    public void comment(String message) throws DatabaseException {
        LogFactory.getLogger().debug(message);
    }

    private class ExecuteStatementCallback implements StatementCallback {

        private final SqlStatement sql;
        private final List<SqlVisitor> sqlVisitors;

        private ExecuteStatementCallback(SqlStatement sql, List<SqlVisitor> sqlVisitors) {
            this.sql = sql;
            this.sqlVisitors = sqlVisitors;
        }

        @Override
        public Object doInStatement(Statement stmt) throws SQLException, DatabaseException {
            for (String statement : applyVisitors(sql, sqlVisitors)) {
                if (database instanceof OracleDatabase) {
                    statement = statement.replaceFirst("/\\s*/\\s*$", ""); //remove duplicated /'s
                }

                log.debug("Executing EXECUTE database command: "+statement);
                if (statement.contains("?")) {
                    stmt.setEscapeProcessing(false);
                }
                try {
                    stmt.execute(statement);
                } catch (SQLException e) {
                    throw e;
                }
            }
            return null;
        }

        @Override
        public SqlStatement getStatement() {
            return sql;
        }
    }


}
