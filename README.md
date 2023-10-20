# Assumptions 

- Change data capture flow :
  - Source: from an Oracle 19c to a Kafka topic using Debezium
  - Sink:  from a Kafka topic to a MongoDB collection    
- No transformation to apply to the captured change payloads
- Store change payloads as they are in MongoDB
- We don’t apply change types (Inserts, updates, deletes …)
# High-Level Architecture

## Option1 - Using Kafka Connect for the Sink - Simple & Easy


![](https://i.imgur.com/UYwOWZk.png)


## Option 2 - Using Apache Camel for the Sink - Advanced Integration patterns

![](https://i.imgur.com/nGlo1va.png)

# Installation and configuration

First clone this repository to your local machine

```bash
git clone https://github.com/snoussi/cdc-demo.git
```

Login to your Openshift cluster and create a new project/namespace named `cdc-demo` to hold the demo

```bash
oc new-project cdc-demo
```

## Oracle installation

We will opt for a local container installation of the Oracle database, using the following steps

1. Make sure that `podman` is up and running

2. Login to container-registry.oracle.com, make sure you have an account and have accepted the Oracle Standard Terms and Restrictions for this [container](https://container-registry.oracle.com/ords/ocr/ba/database/enterprise)  

```bash
podman login container-registry.oracle.com
```

3.  Pull the image
```bash
podman pull container-registry.oracle.com/database/enterprise:19.3.0.0
```

4. Start an Oracle Database server instance

```bash
podman run -d \
  --name dbz_oracle19 \
  -p 1521:1521 \
  -e ORACLE_SID=ORCLCDB \
  -e ORACLE_PDB=ORCLPDB1 \
  -e ORACLE_PWD=oraclepw \
  container-registry.oracle.com/database/enterprise:19.3.0.0
```

3.  Tail the container’s database log until successful installation
```bash
podman logs -f dbz_oracle19
```

4.  Open an `SQLPlus` terminal to the Oracle database container.
```bash
podman exec -it dbz_oracle19 sqlplus sys/oraclepw as sysdba
```

5. **Oracle LogMiner - Archive logs configuration**: execute the following SQL commands inside the `SQLPlus` terminal
```SQL
SELECT LOG_MODE FROM V$DATABASE;
```
If the column contains `ARCHIVELOG`, then archive logging is enabled (you can the skip next step). If the column contains the value `NOARCHIVELOG`, archive logging isn’t enabled, and further configuration is necessary (see next step). 

6.  **Oracle LogMiner - Archive logs configuration**: execute the following SQL commands inside the `SQLPlus` terminal
```sql
ALTER SYSTEM SET db_recovery_file_dest_size = 10G;
```

```SQL
ALTER SYSTEM SET db_recovery_file_dest = '/opt/oracle/oradata/ORCLCDB' scope=spfile; 
```

```SQL
SHUTDOWN IMMEDIATE
```

```SQL
STARTUP MOUNT
```

```SQL
ALTER DATABASE ARCHIVELOG;
```

```SQL
ALTER DATABASE OPEN;
```

```SQL
ARCHIVE LOG LIST;
```

The final output from the `SQLPlus` terminal shows the following:

```RESULT
Database log mode        Archive Mode
Automatic archival         Enabled
Archive destination        USE_DB_RECOVERY_FILE_DEST
Oldest online log sequence     4
Next log sequence to archive   6
Current log sequence         6
```

7.  **Oracle LogMiner - Redo logs configuration**: There are two log mining strategies for Debezium’s Oracle connector. The strategy controls how the connector interacts with Oracle LogMiner and how the connector ingests schema and table changes:

  **`redo_log_catalog`**
  The data dictionary will be written periodically to the redo logs, causing a higher generation of archive logs over time. This setting enables tracking DDL changes, so if a table’s schema changes, this will be the ideal strategy for that purpose.

  **`online_catalog`**
  The data dictionary will not be written periodically to the redo logs, leaving the generation of archive logs consistent with current behavior. Oracle LogMiner will mine changes substantially faster; however, this performance comes at the cost of **not** tracking DDL changes. If a table’s schema remains constant, this will be the ideal strategy for that purpose.

When using the **`online_catalog`** mode, you can safely skip this step entirely.

8.  **Oracle LogMiner - Redo logs configuration**: execute the following SQL commands inside the `SQLPlus` terminal to check the Redo log size 
```SQL
SELECT GROUP#, BYTES/1024/1024 SIZE_MB, STATUS FROM V$LOG ORDER BY 1;
```

This output tells us there are 3 log groups, and each group consumes 200MB of space per log. Additionally, the status associated with each group is crucial as it represents the current state of that log.
```RESULT
    GROUP#    SIZE_MB STATUS
---------- ---------- ----------------
       1        200 INACTIVE
       2        200 CURRENT
       3        200 UNUSED
```

Now, execute the following SQL to determine the filenames and locations of the redo logs.
```SQL
SELECT GROUP#, MEMBER FROM V$LOGFILE ORDER BY 1, 2;
```

```RESULT
    GROUP# MEMBER
---------- ---------------------------------------------------
       1 /opt/oracle/oradata/ORCLCDB/redo01.log
       2 /opt/oracle/oradata/ORCLCDB/redo02.log
       3 /opt/oracle/oradata/ORCLCDB/redo03.log
```

Now, execute the following SQL to drop and re-create the log group with the size of `500MB`. We will use the same log file name in the `MEMBER` column from the `VLOGFILE` table.
```SQL
ALTER DATABASE CLEAR LOGFILE GROUP 1;
```

```SQL
ALTER DATABASE DROP LOGFILE GROUP 1;
```

```SQL
ALTER DATABASE ADD LOGFILE GROUP 1 ('/opt/oracle/oradata/ORCLCDB/redo01.log') size 500M REUSE;
```

Execute the following SQL commands inside the `SQLPlus` terminal to check again the Redo log size 
```SQL
SELECT GROUP#, BYTES/1024/1024 SIZE_MB, STATUS FROM V$LOG ORDER BY 1;
```

```RESULT
    GROUP#    SIZE_MB STATUS
---------- ---------- ----------------
       1        400 UNUSED
       2        200 CURRENT
       3        200 UNUSED
```
9. **Oracle LogMiner - Supplemental Logging configuration**: For Debezium to interface with LogMiner and work with chained rows and various storage arrangements, database supplemental logging must be enabled at a minimum level. To enable this level, execute the following SQL in the `SQLPlus` terminal:

```SQL
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;
```

9. **Oracle LogMiner - User setup:** A user account will require specific permissions to access these LogMiner APIs and gather data from the captured tables, execute the following SQL in the `SQLPlus` terminal:

```SQL
CONNECT sys/oraclepw@ORCLCDB as sysdba;
```

```SQL
CREATE TABLESPACE logminer_tbs DATAFILE '/opt/oracle/oradata/ORCLCDB/logminer_tbs.dbf' SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;
```

```SQL
CONNECT sys/oraclepw@ORCLPDB1 as sysdba;
```

```SQL
CREATE TABLESPACE logminer_tbs DATAFILE '/opt/oracle/oradata/ORCLCDB/ORCLPDB1/logminer_tbs.dbf' SIZE 25M REUSE AUTOEXTEND ON MAXSIZE UNLIMITED;
```

```SQL
CONNECT sys/oraclepw@ORCLCDB as sysdba;
```

```SQL
CREATE USER c##dbzuser IDENTIFIED BY dbz DEFAULT TABLESPACE LOGMINER_TBS QUOTA UNLIMITED ON LOGMINER_TBS CONTAINER=ALL;
```

```SQL
GRANT CREATE SESSION TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SET CONTAINER TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$DATABASE TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT FLASHBACK ANY TABLE TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ANY TABLE TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT EXECUTE_CATALOG_ROLE TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ANY TRANSACTION TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ANY DICTIONARY TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT CREATE TABLE TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT LOCK ANY TABLE TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT CREATE SEQUENCE TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT EXECUTE ON DBMS_LOGMNR TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT EXECUTE ON DBMS_LOGMNR_D TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$LOG TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$LOG_HISTORY TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$LOGMNR_LOGS TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$LOGMNR_CONTENTS TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$LOGMNR_PARAMETERS TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$LOGFILE TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$ARCHIVED_LOG TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$ARCHIVE_DEST_STATUS TO c##dbzuser CONTAINER=ALL;
```

```SQL
GRANT SELECT ON V_$TRANSACTION TO c##dbzuser CONTAINER=ALL;
```

10.  You can refer to [Debezium for Oracle - Part 1: Installation and Setup](https://debezium.io/blog/2022/09/30/debezium-oracle-series-part-1/) for further explanations

11. Create a sample table to use during the demo, and execute the following SQL in the `SQLPlus` terminal:

```SQL
CONNECT sys/oraclepw@ORCLCDB as sysdba;
```

```SQL
CREATE USER c##raa IDENTIFIED BY raapwd DEFAULT TABLESPACE users TEMPORARY TABLESPACE temp;
```

```SQL
GRANT CONNECT, RESOURCE TO c##raa;
```

```SQL
GRANT CREATE TABLE TO c##raa;
```

```SQL
ALTER USER c##raa QUOTA UNLIMITED ON users;
```

```SQL
ALTER USER c##raa DEFAULT TABLESPACE users;
```

```SQL
ALTER USER c##raa TEMPORARY TABLESPACE temp;
```

```SQL
CONNECT c##raa/raapwd@ORCLCDB;
```

```SQL
CREATE TABLE customers (id number(9,0) primary key, name varchar2(50), city varchar2(50));
```

```SQL
GRANT SELECT, INSERT, UPDATE, DELETE ON customers TO c##raa;
```

```SQL
INSERT INTO customers VALUES (1001, 'Rick Grimes','Alexandria');
```

```SQL
INSERT INTO customers VALUES (1002, 'Hubert Bonisseur de La Bath','Paris');
```

```SQL
INSERT INTO customers VALUES (1003, 'Sterling Archer','New York');
```

```SQL
COMMIT;
```

12. Use [ngrok](https://ngrok.com/) Expose the database externally

```Bash
ngrok tcp 1521
```


## MongoDB installation

For this installation, we will use the MongoDB operator offered by `opstreelabs` 

1. Follow these steps to install the MongoDB operator on the right namespace

![](https://i.imgur.com/obPpNel.gif)

2. Create a `secret` to hold the admin password by running the following command 

```bash
oc create secret generic my-mongodb-secret --from-literal=password=ilovemongo
```

3.  Create the following file and name it `my-mongodb.yml`. Don't forget to edit the `storageClass` with the one you use in your Openshift cluster.

```yaml
apiVersion: opstreelabs.in/v1alpha1
kind: MongoDB
metadata:
  name: my-mongodb
spec:
  kubernetesConfig:
    image: 'quay.io/opstree/mongo:v5.0.6'
    imagePullPolicy: IfNotPresent
  mongoDBSecurity:
    mongoDBAdminUser: admin
    secretRef:
      key: password
      name: my-mongodb-secret
  storage:
    accessModes:
      - ReadWriteOnce
    storageClass: lvms-vg1
    storageSize: 1Gi
```

4. Apply the previous file to the namespace

```bash
oc apply -f my-mongodb.yml
```

5.  Create the following file and name it `my-mongodb-service.yml`. This will expose the MongoDB port to the outside world though port 32017

```yaml
apiVersion: v1
kind: Service
metadata:
  name: my-mongodb
  labels:
    app: mongodb-standalone
spec:
  type: NodePort
  ports:
    - port: 27017
      targetPort: 27017
      nodePort: 32017
      name: mongodb-port
  selector:
    app: mongodb-standalone
```

6. Apply the previous file to the namespace

```bash
oc apply -f my-mongodb-service.yml
```

7. Note the connection string:
- To use with the same namespace :
`mongodb://admin:ilovemongo@<OCP_NODE_IP>:32017`
- To use and browser externally in [MongoDB Compass](https://www.mongodb.com/products/tools/compass): 
`mongodb://admin:ilovemongo@my-mongodb.cdc-demo.svc:27017`

## AMQ Streams installation

1. We will use the AMQ Streams Operator for this deployment, first create the following file `amq-streams-operator.yml` and apply it to the namespace

```yaml
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: amq-streams
spec:
  channel: stable
  installPlanApproval: Automatic
  name: amq-streams
  source: redhat-operators
  sourceNamespace: openshift-marketplace
```

```bash
oc apply -f amqstreams-operator.yml
```

### Kafka Cluster
1. Create a basic Kafka cluster by creating the following file `amq-streams-cluster.yml` and apply it to the namespace

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: my-amqstreams-cluster
spec:
  kafka:
    replicas: 3
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
    config:
      default.replication.factor: 3
    storage:
      type: jbod
      volumes:
      - id: 0
        type: persistent-claim
        size: 10Gi
        deleteClaim: true
  zookeeper:
    replicas: 3
    storage:
      type: persistent-claim
      size: 5Gi
      deleteClaim: true
  entityOperator:
    topicOperator: {}
    userOperator: {}
```

```bash
oc apply -f my-amqstreams-cluster.yml
```

2. Find and note the bootstrap Servers url :

`my-amqstreams-cluster-kafka-bootstrap.cdc-demo.svc:9092`

### Source - Kafka Connect for Debezium

1. Create the `debezium-oracle-kafka-connect` imagestream

```bash
oc create imagestream debezium-oracle-kafka-connect
```

2. Create the following Debezium KafkaConnect custom resource (CR), name it `my-debezium-oracle-kafka-connect.yml`, and apply it to the namespace
```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaConnect
metadata:
  name: my-debezium-oracle-kafka-connect
  annotations:
    strimzi.io/use-connector-resources: "true"
spec:
  replicas: 1
  bootstrapServers: 'my-amqstreams-cluster-kafka-bootstrap.cdc-demo.svc:9092'
  build:
    output:
      type: imagestream
      image: debezium-oracle-kafka-connect:latest
    plugins:
     - name: debezium-connector-oracle
       artifacts:
         - type: zip
           url: 'https://maven.repository.redhat.com/ga/io/debezium/debezium-connector-oracle/2.1.4.Final-redhat-00001/debezium-connector-oracle-2.1.4.Final-redhat-00001-plugin.zip'
         - type: zip
           url: 'https://maven.repository.redhat.com/ga/io/debezium/debezium-scripting/2.1.4.Final-redhat-00001/debezium-scripting-2.1.4.Final-redhat-00001.zip'
         - type: jar
           url: 'https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/21.1.0.0/ojdbc8-21.1.0.0.jar'
  config:
    group.id: debezium-oracle-connect
    offset.storage.topic: connect-cluster-offsets
    config.storage.topic: connect-cluster-configs
    status.storage.topic: connect-cluster-status
    config.storage.replication.factor: -1
    offset.storage.replication.factor: -1
    status.storage.replication.factor: -1
```

```bash
oc apply -f my-debezium-oracle-kafka-connect.yml
```

3. Create the following KafkaConnector CR, save it as `oracle-source-connector-customers-table.yml`, and apply it to the namespace 
```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaConnector
metadata:
  name: oracle-source-connector-customers-table
  labels:
    strimzi.io/cluster: my-debezium-oracle-kafka-connect
  annotations:
    strimzi.io/use-connector-resources: 'true'
spec:
  class: io.debezium.connector.oracle.OracleConnector
  tasksMax: 1
  config:
   schema.history.internal.kafka.bootstrap.servers: 'my-amqstreams-cluster-kafka-bootstrap.cdc-demo.svc:9092'
   schema.history.internal.kafka.topic: "schema-changes"
   database.hostname: 6.tcp.eu.ngrok.io
   database.port: 16731
   database.user: c##dbzuser
   database.password: dbz
   database.dbname: ORCLCDB
   topic.prefix: oracle-cdc
   table.include.list: C##RAA.CUSTOMERS

```

```bash
oc apply -f oracle-source-connector-customers-table.yml
```

### Sink option 1 - Kafka Connect for MongoDB

1. Create the `mongodb-kafka-connect` imagestream

```bash
oc create imagestream mongodb-kafka-connect
```

2. Create the following MongoDB KafkaConnect custom resource (CR), name it `my-mongodb-kafka-connect-cluster.yml`, and apply it to the namespace

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaConnect
metadata:
  name: my-mongodb-kafka-connect-cluster
  annotations:
    strimzi.io/use-connector-resources: "true"
spec:
  replicas: 1
  bootstrapServers: 'my-amqstreams-cluster-kafka-bootstrap.cdc-demo.svc:9092'
  build:
    output:
      type: imagestream
      image: mongodb-kafka-connect:latest
    plugins:
      - name: my-mongo-kafka-connect-plugin
        artifacts:
          - type: maven
            repository: 'https://repo1.maven.org/maven2'
            group: org.mongodb.kafka
            artifact: mongo-kafka-connect
            version: 1.11.0
  config:
    group.id: mongodb-connect-cluster
    key.converter: org.apache.kafka.connect.json.JsonConverter
    value.converter: org.apache.kafka.connect.json.JsonConverter
    key.converter.schemas.enable: false
    value.converter.schemas.enable: false
    offset.storage.topic: connect-offsets
    config.storage.topic: connect-configs
    status.storage.topic: connect-status
```

```bash
oc apply -f my-mongodb-kafka-connect-cluster.yml
```

3. Create the following KafkaConnector CR, save it as `oracle-source-connector-customers-table.yml`, and apply it to the namespace 

```yaml
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaConnector
metadata:
  labels:
    strimzi.io/cluster: my-mongodb-kafka-connect-cluster
  name: mongodb-sink-connector
spec:
  class: com.mongodb.kafka.connect.MongoSinkConnector
  config:
    collection: customers-connect
    connection.uri: 'mongodb://admin:ilovemongo@my-mongodb.cdc-demo.svc:27017'
    database: sampledb
    key.converter: org.apache.kafka.connect.storage.StringConverter
    topics: oracle-cdc.C__RAA.CUSTOMERS
    value.converter: org.apache.kafka.connect.json.JsonConverter
    value.converter.schemas.enable: false
```

```bash
oc apply -f mongodb-sink-connector.yml
```


### Sink option 2 - Kafka to MongoDB Apache Camel route

1. Move to `camel-mongodb-sink` folder, open the Quarkus project in your favorite IDE, then inspect the `Routes` Class and the `application.properties` file

```bash
cd ./camel-mongodb-sink
```

2. Build and deploy the project to Openshift, make sure you are on the right namespace
```bash
./mvnw clean package -Dquarkus.kubernetes.deploy=true -DskipTests -Dopenshift
```

- To delete the deployed project you can run the following command
```bash
oc delete all -l app.kubernetes.io/name=camel-mongodb-sink
```


# Demo script

1. Open a new terminal, log into the sample `customers` database and run the following SQL commands:

```SQL
INSERT INTO customers VALUES (1004, 'John Rambo','Arizona');
INSERT INTO customers VALUES (1005, 'Indiana Jones','Cairo');
INSERT INTO customers VALUES (1006, 'James Bond','London');
UPDATE customers set city= 'Kabul' where id=1004;
```

```SQL
COMMIT;
```

2. Verify that the changes to the customers database have been replicated into MongoDB


# Learn More

- [Debezium for Oracle - Part 1: Installation and Setup](https://debezium.io/blog/2022/09/30/debezium-oracle-series-part-1/) 
- [Debezium for Oracle - Part 2: Running the connector](https://debezium.io/blog/2022/10/06/debezium-oracle-series-part-2/)
- [Debezium for Oracle - Part 3: Performance and Debugging](https://debezium.io/blog/2023/06/29/debezium-oracle-series-part-3/)
- [Capture Oracle DB events in Kafka with Debezium | Red Hat Developer](https://developers.redhat.com/blog/2021/04/19/capture-oracle-database-events-in-apache-kafka-with-debezium?source=sso)
- [Deploy a Kafka Connect container using Strimzi | Red Hat Developer](https://developers.redhat.com/articles/2023/03/29/deploy-kafka-connect-container-using-strimzi)
- [Sink Connector Configuration Properties — MongoDB Kafka Connector](https://www.mongodb.com/docs/kafka-connector/current/sink-connector/configuration-properties/)
- [Getting Started with the MongoDB Kafka Sink Connector — MongoDB Kafka Connector](https://www.mongodb.com/docs/kafka-connector/current/tutorials/sink-connector/)
- [Event-based microservices with Red Hat AMQ Streams | Red Hat Developer](https://developers.redhat.com/blog/2019/11/21/event-based-microservices-with-red-hat-amq-streams?source=sso)
- [Product Documentation for Red Hat build of Debezium 2.1.4 | Red Hat Customer Portal](https://access.redhat.com/documentation/en-us/red_hat_build_of_debezium/2.1.4)
- [Product Documentation for Red Hat AMQ Streams 2.5 | Red Hat Customer Portal](https://access.redhat.com/documentation/en-us/red_hat_amq_streams/2.5)


