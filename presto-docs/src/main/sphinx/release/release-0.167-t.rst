=================
Release 0.167-t
=================

Presto 0.167-t is equivalent to Presto release 0.167, with some additional features and patches.

SQL Features
------------
* Add ``GROUPING`` support.

Data Types
----------
* Native functions for ``CHAR(x)``.
* ``DECIMAL`` is the default literal for non-integral numbers.

The Teradata distribution of Presto fixes the semantics of the ``TIMESTAMP`` and ``TIME``
types to align with the SQL standard. See the following sections for details.

**TIMESTAMP semantic changes**

Previously, the ``TIMESTAMP`` type described an instance in time in the Presto session's time zone.
Now, Presto treats ``TIMESTAMP`` values as a set of the following fields representing wall time:

 * ``YEAR OF ERA``
 * ``MONTH OF YEAR``
 * ``DAY OF MONTH``
 * ``HOUR OF DAY``
 * ``MINUTE OF HOUR``
 * ``SECOND OF MINUTE`` - as decimal with precision 3

For that reason, a ``TIMESTAMP`` value is not linked with the session time zone in any way until a time zone is needed explicitly,
such as when casting to a ``TIMESTAMP WITH TIME ZONE`` or ``TIME WITH TIME ZONE``.
In those cases, the time zone offset of the session time zone is applied, as specified in the SQL standard.

For various compatibility reasons, when casting from date/time type without a time zone to one with a time zone, a fixed time zone
is used as opposed to the named one that may be set for the session.

eg. with ``-Duser.timezone="Asia/Kathmandu"`` on CLI

 * Query: ``SELECT CAST(TIMESTAMP '2000-01-01 10:00' AS TIMESTAMP WITH TIME ZONE);``
 * Previous result: ``2000-01-01 10:00:00.000 Asia/Kathmandu``
 * Current result: ``2000-01-01 10:00:00.000 +05:45``

**TIME semantic changes**

The ``TIME`` type was changed similarly to the ``TIMESTAMP`` type.

**TIME WITH TIME ZONE semantic changes**

Due to compatibility requirements, having ``TIME WITH TIME ZONE`` completely aligned with the SQL standard was not possible yet.
For that reason, when calculating the time zone offset for ``TIME WITH TIME ZONE``, the Teradata distribution of Presto uses
the session's start date and time.

This can be seen in queries using ``TIME WITH TIME ZONE`` in a time zone that has had time zone policy changes or uses DST.
eg. With session start time on 1 March 2017

 * Query: ``SELECT TIME '10:00:00 Asia/Kathmandu' AT TIME ZONE 'UTC'``
 * Previous result: ``04:30:00.000 UTC``
 * Current result: ``04:15:00.000 UTC``

**Time-related bug fixes**

 * The ``current_time`` and ``localtime`` functions were fixed to return the correct value for non-UTC timezones.

Security
--------
* File based system access control plugin that allows you to specify catalog access control rules
  and Kerberos principal matching rules.
* Secure cluster communication over HTTPS.
* ``SHOW GRANTS`` support for the Hive connector.
* ``ROLE`` support for the Hive connector, including ``CREATE ROLE``,
  ``DROP ROLE``, ``GRANT ROLE``, ``REVOKE ROLE``, ``SET ROLE``, ``SHOW CURRENT ROLES``,
  ``SHOW ROLES`` and ``SHOW ROLE GRANTS`` commands.

Performance
-----------
* Automatically choose between repartitioned and replicated based on Hive table statistics.
  Must have ``join-distribution-type`` set to ``automatic``.
* Push aggregations below outer joins when possible. It is particularly useful for correlated scalar aggregations.
* Faster ``DECIMAL`` implementation.
* Distributed aggregation for ROLLUP and CUBE.
* Improve window function performance.
* Improve performance of projections without filters.
* Push down equality predicates on clustering keys for the Cassandra connector.

Teradata Presto ODBC/JDBC Drivers
---------------------------------
* The Teradata JDBC driver does not support batch queries.
* ``SET ROLE`` functionality is not supported on ODBC versions <= 1.1.8  and JDBC versions <= 1.0.14.

Connectors
----------
* SQL Server connector, TPC-DS connector and Memory Connector were added

Documentation
-------------
* Query Optimizer
* Query Performance Analysis
* Presto Tuning
* Prepared Statements
* Installation of Presto via Presto Admin
* CLI options

Miscellaneous
-------------
* Support prepared statements that are longer than 4K bytes.
* Additional ``EXPLAIN ANALYZE`` information for window functions and spill to disk.
* More informative error messages for CLI users.

----


Bugs Fixed
----------
* Fix analysis error message for queries containing both aggregations and subqueries in some cases.
* Fix issue “Hive table is corrupt. It is declared as being bucketed, but the files do not match the bucketing declaration. The number of files in the directory (1) does not match the declared.” by fixing support for Hive bucketed tables. See option hive.multi-file-bucketing.enabled in the Presto Hive connector documentation.
* Allow empty partitions for clustered hive tables.
* Clean up files on failed ``CREATE TABLE AS SELECT`` queries on S3.
* Fix bug in ``JOIN ON`` clause with two char fields.
* Fix wrong results when an argument to a function in the ``ORDER BY`` clause needs to be coerced.
* Temporarily revert empty join short-circuit optimization due to issue with hanging queries.
* Fix reading decimals for RCFile text format using non-optimized reader.

----


Unsupported Functionality
-------------------------

Some functionality from Presto 0.167 may work but is not officially supported by Teradata.

* The installation method as documented on `prestodb.io <https://prestodb.io/docs/0.167/installation/deployment.html>`_.
* Web Connector for Tableau
* The following connectors:

  * Accumulo Connector
  * Kafka Connector
  * Local File Connector
  * MongoDB Connector
  * Redis Connector


----


SQL/DML/DDL Limitations
-----------------------

 * The SQL keyword ``end`` is used as a column name in ``system.runtime.queries``, so in order to query from that column, ``end`` must be wrapped in quotes
 * ``NATURAL JOIN`` is not supported
 * ``LIMIT ALL`` and ``OFFSET`` are not supported
 * For ``INSERT INTO ... VALUES``, the data types must be exact, i.e. must use ``2.0`` for ``double``,
   ``cast('2015-1-1' as date)`` for ``date``, and you must supply a value for every column.
 * The expression on the left hand side of ``IN`` must not be ``NULL`` for any of the queried rows. Otherwise, the query will fail. This limitation is needed to ensure correct results and may be dropped in the future. This also affect quantified subqueries. See :doc:`/sql/select` and :doc:`/functions/comparison`. In previously versions of Presto this query was allowed to execute potentially resulting in incorrect results.

----

Additional Limitations
----------------------
 * If called through JDBC, executeUpdate does not return the count of rows inserted.
 * Presto does not push down aggregate calculations. This means that when a user executes a
   simple query such as ``SELECT COUNT(*) FROM lineitem`` the entire table will be retrieved and the aggregate calculated
   by Presto.  If the table is large or the network slow, this may take a very long time.

----

QueryGrid
---------
QueryGrid connectors Presto-to-Teradata and Teradata-to-Presto version 1.5 will be the terminal release for the 1.x generation. For future releases of Presto, you must upgrade to the QueryGrid 2.x current generation.

----

Hive Connector Limitations
--------------------------

**File Formats**

Teradata supports data stored in the following formats:

 * Text files
 * ORC
 * RCFILE
 * PARQUET

**TIMESTAMP limitations**

Presto supports a granularity of milliseconds for the ``TIMESTAMP`` datatype, while Hive
supports microseconds.

**INSERT INTO ... SELECT limitations**

INSERT INTO creates unreadable data (unreadable both by Hive and Presto) if a Hive table has a schema for which Presto
only interprets some of the columns (e.g. due to unsupported data types).  This is because the generated file on HDFS
will not match the Hive table schema.

**Hive Transactions**

The Hive connector does not support Hive ACID tables.
`<https://cwiki.apache.org/confluence/display/Hive/Hive+Transactions>`_

----


PostgreSQL and MySQL Connector Limitations
------------------------------------------

**Known Bugs**

PostgreSQL connector ``describe table`` reports ``Table has no supported column types`` inappropriately.
`https://github.com/facebook/presto/issues/4082 <https://github.com/facebook/presto/issues/4082>`_

**Security**

Presto connects to MySQL and PostgreSQL using the credentials specified in the properties file.  The credentials are
used to authenticate the users while establishing the connection.  Presto runs queries as the "presto" service user and
does not pass down user information to MySQL or PostgreSQL connectors.

**Datatypes**

PostgreSQL and MySQL each support a wide variety of datatypes.  Many of these
types are not supported in Presto.  Table columns that are defined using an unsupported type are not visible to Presto
users.  These columns are not shown when ``describe table`` or ``select *`` SQL statements are executed.

**MySQL Catalogs**

MySQL catalog names are mapped to Presto schema names.

----
