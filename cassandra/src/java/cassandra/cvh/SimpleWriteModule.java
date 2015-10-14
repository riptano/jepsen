package cassandra.cvh;

import java.util.Arrays;
import java.util.Map;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;

import clojure.lang.APersistentMap;
import clojure.lang.PersistentVector;
import clojure.lang.Keyword;

import jepsen.client.Client;


public class SimpleWriteModule implements Client
{
    static private Keyword OK = Keyword.intern(null, "ok");
    static private Keyword TYPE = Keyword.intern(null, "type");
    static private Keyword VALUE = Keyword.intern(null, "value");
    static private Keyword NODES = Keyword.intern(null, "nodes");

    private Cluster cluster;
    private Session session;
    private PreparedStatement query;

    public SimpleWriteModule()
    {
    }

    public Object setup_BANG_(Object test, Object node)
    {
        cluster = Cluster.builder().addContactPoint(((Keyword)node).getName()).build();
        session = cluster.connect();

        session.execute("CREATE KEYSPACE IF NOT EXISTS k WITH replication = {'class': 'SimpleStrategy' , 'replication_factor': 1}");
        session.execute("USE k");
        session.execute("CREATE TABLE IF NOT EXISTS t ( id int PRIMARY KEY , v int)");

        query = session.prepare("INSERT INTO t (id, v) VALUES (?, ?)");
        return this;
    }

    public Object invoke_BANG_(Object test, Object op)
    {
        APersistentMap opMap = (APersistentMap) op;
        for (int i = 0; i < 10000; i++)
        {
            BoundStatement bound = query.bind(i, i);
            session.execute(bound);
        }

        ResultSet results = session.execute("SELECT * FROM k.t");

        return opMap.assoc(TYPE, OK).assoc(VALUE, "Stress operations completed");
    }

    public Object teardown_BANG_(Object test)
    {
        session.close();
        cluster.close();
        return this;
    }
}
