/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.smoke.mgmt.datasource;


import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.test.integration.management.jca.ComplexPropertiesParseUtils.addExtensionProperties;
import static org.jboss.as.test.integration.management.jca.ComplexPropertiesParseUtils.checkModelParams;
import static org.jboss.as.test.integration.management.jca.ComplexPropertiesParseUtils.nonXaDsProperties;
import static org.jboss.as.test.integration.management.jca.ComplexPropertiesParseUtils.setOperationParams;
import static org.jboss.as.test.integration.management.jca.ComplexPropertiesParseUtils.xaDsProperties;

import java.security.InvalidParameterException;
import java.util.List;
import java.util.Properties;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.connector.subsystems.datasources.DataSourcesExtension;
import org.jboss.as.connector.subsystems.datasources.Namespace;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.test.integration.management.jca.ConnectionSecurityType;
import org.jboss.as.test.integration.management.jca.DsMgmtTestBase;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;


/**
 * Datasource operation unit test.
 *
 * @author <a href="mailto:stefano.maestri@redhat.com">Stefano Maestri</a>
 * @author <a href="mailto:jeff.zhang@jboss.org">Jeff Zhang</a>
 * @author <a href="mailto:vrastsel@redhat.com">Vladimir Rastseluev</a>
 * @author Flavia Rainone
 */
@ExtendWith(ArquillianExtension.class)
@RunAsClient
@ServerSetup(DataSourceOperationsUnitTestCase.ServerSetup.class)
public class DataSourceOperationsUnitTestCase extends DsMgmtTestBase {

    public static class ServerSetup implements ServerSetupTask {

        @Override
        public void setup(ManagementClient managementClient, String containerId) throws Exception {
            ModelNode authContextAdd = Util.createAddOperation(PathAddress.pathAddress("subsystem", "elytron").append("authentication-context", "HsqlAuthCtxt"));
            ModelNode response = managementClient.getControllerClient().execute(authContextAdd);
            Assertions.assertEquals("success", response.get("outcome").asString(), response.toString());
        }

        @Override
        public void tearDown(ManagementClient managementClient, String containerId) throws Exception {
            ModelNode authContextRemove = Util.createRemoveOperation(PathAddress.pathAddress("subsystem", "elytron").append("authentication-context", "HsqlAuthCtxt"));
            ModelNode response = managementClient.getControllerClient().execute(authContextRemove);
            Assertions.assertEquals("success", response.get("outcome").asString(), response.toString());
        }
    }

    @Deployment
    public static Archive<?> fakeDeployment() {
        return ShrinkWrap.create(JavaArchive.class);
    }

    @Test
    public void testAddDsAndTestConnection() throws Exception {

        final ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.add("data-source", "MyNewDs");
        address.protect();

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("add");
        operation.get(OP_ADDR).set(address);

        operation.get("name").set("MyNewDs");
        operation.get("jndi-name").set("java:jboss/datasources/MyNewDs");
        operation.get("enabled").set(true);
        // WFLY-16272 test a simple use-java-context with expression value in "test-connection-in-pool" operation.
        operation.get("use-java-context").set("${env.db_java_context:true}");

        operation.get("driver-name").set("h2");
        operation.get("pool-name").set("MyNewDs_Pool");

        operation.get("connection-url").set("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        operation.get("user-name").set("sa");
        operation.get("password").set("sa");

        executeOperation(operation);

        testConnection("MyNewDs");

        List<ModelNode> newList = marshalAndReparseDsResources("data-source");

        remove(address);

        Assertions.assertNotNull(newList, "Reparsing failed:");

        Assertions.assertNotNull(findNodeWithProperty(newList, "jndi-name", "java:jboss/datasources/MyNewDs"));
    }


    @Test
    public void testAddAndRemoveSameName() throws Exception {
        final String dsName = "SameNameDs";
        final ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.add("data-source", dsName);
        address.protect();

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("add");
        operation.get(OP_ADDR).set(address);

        operation.get("name").set(dsName);
        operation.get("jndi-name").set("java:jboss/datasources/" + dsName);
        operation.get("enabled").set(false);

        operation.get("driver-name").set("h2");
        operation.get("pool-name").set(dsName + "_Pool");

        operation.get("connection-url").set("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        operation.get("user-name").set("sa");
        operation.get("password").set("sa");

        // do twice, test for AS7-720
        for (int i = 1; i <= 2; i++) {
            executeOperation(operation);

            remove(address);
        }
    }

    /**
     * AS7-1206 test for jndi binding isn't unbound during remove if jndi name
     * and data-source name are different
     *
     * @throws Exception
     */
    @Test
    public void testAddAndRemoveNameAndJndiNameDifferent() throws Exception {
        final String dsName = "DsName";
        final String jndiDsName = "JndiDsName";

        final ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.add("data-source", dsName);
        address.protect();

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("add");
        operation.get(OP_ADDR).set(address);

        operation.get("name").set(dsName);
        operation.get("jndi-name").set("java:jboss/datasources/" + jndiDsName);
        operation.get("enabled").set(false);


        operation.get("driver-name").set("h2");
        operation.get("pool-name").set(dsName + "_Pool");

        operation.get("connection-url").set("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        operation.get("user-name").set("sa");
        operation.get("password").set("sa");


        executeOperation(operation);
        remove(address);

    }

    @Test
    public void testAddAndRemoveXaDs() throws Exception {
        final String dsName = "XaDsName";
        final String jndiDsName = "XaJndiDsName";

        final ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.add("xa-data-source", dsName);
        address.protect();

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("add");
        operation.get(OP_ADDR).set(address);

        operation.get("name").set(dsName);
        operation.get("jndi-name").set("java:jboss/datasources/" + jndiDsName);
        operation.get("enabled").set(false);


        operation.get("driver-name").set("h2");
        operation.get("pool-name").set(dsName + "_Pool");

        operation.get("user-name").set("sa");
        operation.get("password").set("sa");


        executeOperation(operation);

        final ModelNode xaDatasourcePropertiesAddress = address.clone();
        xaDatasourcePropertiesAddress.add("xa-datasource-properties", "URL");
        xaDatasourcePropertiesAddress.protect();
        final ModelNode xaDatasourcePropertyOperation = new ModelNode();
        xaDatasourcePropertyOperation.get(OP).set("add");
        xaDatasourcePropertyOperation.get(OP_ADDR).set(xaDatasourcePropertiesAddress);
        xaDatasourcePropertyOperation.get("value").set("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        executeOperation(xaDatasourcePropertyOperation);

        remove(address);
    }

    /**
     * AS7-1200 test case for xa datasource persistence to xml
     *
     * @throws Exception
     */
    @Test
    public void testMarshallUnmarshallXaDs() throws Exception {
        final String dsName = "XaDsName2";
        final String jndiDsName = "XaJndiDsName2";

        final ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.add("xa-data-source", dsName);
        address.protect();

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("add");
        operation.get(OP_ADDR).set(address);

        operation.get("name").set(dsName);
        operation.get("jndi-name").set("java:jboss/datasources/" + jndiDsName);
        operation.get("enabled").set(false);


        operation.get("driver-name").set("h2");
        operation.get("pool-name").set(dsName + "_Pool");

        operation.get("user-name").set("sa");
        operation.get("password").set("sa");

        executeOperation(operation);

        final ModelNode xaDatasourcePropertiesAddress = address.clone();
        xaDatasourcePropertiesAddress.add("xa-datasource-properties", "URL");
        xaDatasourcePropertiesAddress.protect();
        final ModelNode xaDatasourcePropertyOperation = new ModelNode();
        xaDatasourcePropertyOperation.get(OP).set("add");
        xaDatasourcePropertyOperation.get(OP_ADDR).set(xaDatasourcePropertiesAddress);
        xaDatasourcePropertyOperation.get("value").set("jdbc:h2:mem:test");

        executeOperation(xaDatasourcePropertyOperation);

        final ModelNode operation2 = new ModelNode();
        operation2.get(OP).set("write-attribute");
        operation2.get("name").set("enabled");
        operation2.get("value").set(true);
        operation2.get(OP_ADDR).set(address);

        executeOperation(operation2);

        List<ModelNode> newList = marshalAndReparseDsResources("xa-data-source");

        remove(address);

        Assertions.assertNotNull(newList, "Reparsing failed:");

        // remove from xml too
        marshalAndReparseDsResources("xa-data-source");

        Assertions.assertNotNull(findNodeWithProperty(newList, "jndi-name", "java:jboss/datasources/" + jndiDsName));

    }


    @Test
    public void testReadInstalledDrivers() throws Exception {

        final ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.protect();

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("installed-drivers-list");
        operation.get(OP_ADDR).set(address);

        final ModelNode result = executeOperation(operation);

        final ModelNode result2 = result.get(0);
        Assertions.assertNotNull(result2, "There are no installed JDBC drivers");
        Assertions.assertTrue(result2.hasDefined("driver-name"), "Name of JDBC driver is udefined");
        if (!result2.hasDefined("deployment-name")) {//deployed drivers haven't these attributes
            Assertions.assertTrue(result2.hasDefined("driver-module-name"), "Module name of JDBC driver is udefined");
            Assertions.assertTrue(result2.hasDefined("module-slot"), "Module slot of JDBC driver is udefined");
        }
    }

    @Test
    public void testReadJdbcDriver() throws Exception {
        String h2DriverName = "h2backup";
        final ModelNode addrAddH2DriverAddr = new ModelNode();
        addrAddH2DriverAddr.add("subsystem", "datasources").add("jdbc-driver", h2DriverName);
        final ModelNode addH2DriverOp = new ModelNode();
        addH2DriverOp.get(OP).set("add");
        addH2DriverOp.get(OP_ADDR).set(addrAddH2DriverAddr);
        addH2DriverOp.get("driver-name").set(h2DriverName);
        addH2DriverOp.get("driver-module-name").set("com.h2database.h2");
        executeOperation(addH2DriverOp);
        try {
            final ModelNode address = new ModelNode();
            address.add("subsystem", "datasources");
            address.protect();

            final ModelNode operation = new ModelNode();
            operation.get(OP).set("get-installed-driver");
            operation.get(OP_ADDR).set(address);
            operation.get("driver-name").set(h2DriverName);

            final ModelNode result = executeOperation(operation).get(0);
            Assertions.assertEquals(h2DriverName, result.get("driver-name").asString());
            Assertions.assertEquals("com.h2database.h2", result.get("driver-module-name").asString());
            Assertions.assertEquals("", result.get("driver-xa-datasource-class-name").asString());
        } finally {
            remove(addrAddH2DriverAddr);
        }
    }

    /**
     * AS7-1203 test for missing xa-datasource properties
     *
     * @throws Exception
     */
    @Test
    public void testAddXaDsWithProperties() throws Exception {

        final String xaDs = "MyNewXaDs";
        final String xaDsJndi = "java:jboss/xa-datasources/" + xaDs;
        final ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.add("xa-data-source", xaDs);
        address.protect();

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("add");
        operation.get(OP_ADDR).set(address);

        operation.get("name").set(xaDs);
        operation.get("jndi-name").set(xaDsJndi);
        operation.get("enabled").set(false);
        operation.get("driver-name").set("h2");
        operation.get("xa-datasource-class").set("org.jboss.as.connector.subsystems.datasources.ModifiableXaDataSource");
        operation.get("pool-name").set(xaDs + "_Pool");
        operation.get("user-name").set("sa");
        operation.get("password").set("sa");

        executeOperation(operation);

        final ModelNode xaDatasourcePropertiesAddress = address.clone();
        xaDatasourcePropertiesAddress.add("xa-datasource-properties", "URL");
        xaDatasourcePropertiesAddress.protect();
        final ModelNode xaDatasourcePropertyOperation = new ModelNode();
        xaDatasourcePropertyOperation.get(OP).set("add");
        xaDatasourcePropertyOperation.get(OP_ADDR).set(xaDatasourcePropertiesAddress);
        xaDatasourcePropertyOperation.get("value").set("jdbc:h2:mem:test");

        executeOperation(xaDatasourcePropertyOperation);


        final ModelNode operation2 = new ModelNode();
        operation2.get(OP).set("write-attribute");
        operation2.get("name").set("enabled");
        operation2.get("value").set(true);
        operation2.get(OP_ADDR).set(address);

        executeOperation(operation2);


        List<ModelNode> newList = marshalAndReparseDsResources("xa-data-source");

        remove(address);

        Assertions.assertNotNull(newList, "Reparsing failed:");

        Assertions.assertNotNull(findNodeWithProperty(newList, "jndi-name", xaDsJndi));

    }

    /**
     * AS7-2720 tests for parsing particular datasource in standalone mode
     *
     * @throws Exception
     */
    @Test
    public void testAddComplexDsUsername() throws Exception {
        testAddComplexDs(ConnectionSecurityType.USER_PASSWORD);
    }

    @Test
    public void testAddComplexDsElytron() throws Exception {
        testAddComplexDs(ConnectionSecurityType.ELYTRON);
    }

    @Test
    public void testAddComplexDsElytronAuthenticationContext() throws Exception {
        testAddComplexDs(ConnectionSecurityType.ELYTRON_AUTHENTICATION_CONTEXT);
    }

    @Test
    public void testAddComplexDsSecurityDomain() throws Exception {
        testAddComplexDs(ConnectionSecurityType.SECURITY_DOMAIN);
    }

    private void testAddComplexDs(ConnectionSecurityType connectionSecurityType) throws Exception {
        final String complexDs;
        switch(connectionSecurityType) {
            case ELYTRON:
                complexDs = "complexDsElytronWithOutAuthCtx";
                break;
            case ELYTRON_AUTHENTICATION_CONTEXT:
                complexDs = "complexDsElytronWithAuthCtx";
                break;
            case SECURITY_DOMAIN:
                complexDs = "complexDs";
                break;
            case USER_PASSWORD:
                complexDs = "complexDsWithUserName";
                break;
            default:
                throw new InvalidParameterException("Unsupported connection security type for Data Sources: " +
                        connectionSecurityType);
        }
        final String complexDsJndi = "java:jboss/datasources/" + complexDs;
        final ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.add("data-source", complexDs);
        address.protect();

        Properties params = nonXaDsProperties(complexDsJndi, connectionSecurityType);

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("add");
        operation.get(OP_ADDR).set(address);
        operation.get("enabled").set(false);

        setOperationParams(operation, params);
        addExtensionProperties(operation);

        executeOperation(operation);

        final ModelNode datasourcePropertiesAddress = address.clone();
        datasourcePropertiesAddress.add("connection-properties", "char.encoding");
        datasourcePropertiesAddress.protect();
        final ModelNode datasourcePropertyOperation = new ModelNode();
        datasourcePropertyOperation.get(OP).set("add");
        datasourcePropertyOperation.get(OP_ADDR).set(datasourcePropertiesAddress);
        datasourcePropertyOperation.get("value").set("UTF-8");

        executeOperation(datasourcePropertyOperation);

        List<ModelNode> newList = marshalAndReparseDsResources("data-source");

        remove(address);

        Assertions.assertNotNull(newList, "Reparsing failed:");

        ModelNode rightChild = findNodeWithProperty(newList, "jndi-name", complexDsJndi);

        Assertions.assertTrue(checkModelParams(rightChild, params), "node:" + rightChild.asString() + ";\nparams" + params);

        Assertions.assertEquals("Property2", rightChild.get("valid-connection-checker-properties", "name").asString(), rightChild.asString());
        Assertions.assertEquals("Property4", rightChild.get("exception-sorter-properties", "name").asString(), rightChild.asString());
        Assertions.assertEquals("Property3", rightChild.get("stale-connection-checker-properties", "name").asString(), rightChild.asString());
        Assertions.assertEquals("Property1", rightChild.get("reauth-plugin-properties", "name").asString(), rightChild.asString());

        Assertions.assertNotNull(findNodeWithProperty(newList, "value", "UTF-8"), "connection-properties not propagated ");

    }

    /**
     * AS7-2720 tests for parsing particular XA-datasource in standalone mode
     *
     * @throws Exception
     */
    @Test
    public void testAddComplexXaDsUsername() throws Exception {
        testAddComplexXaDs(ConnectionSecurityType.USER_PASSWORD);
    }

    @Test
    public void testAddComplexXaDsElytron() throws Exception {
        testAddComplexXaDs(ConnectionSecurityType.ELYTRON);
    }

    @Test
    public void testAddComplexXaDsElytronAuthenticationContext() throws Exception {
        testAddComplexXaDs(ConnectionSecurityType.ELYTRON_AUTHENTICATION_CONTEXT);
    }

    @Test
    public void testAddComplexXaDsComplexDs() throws Exception {
        testAddComplexXaDs(ConnectionSecurityType.SECURITY_DOMAIN);
    }

    private void testAddComplexXaDs(ConnectionSecurityType connectionSecurityType) throws Exception {
        final String complexXaDs;
        switch (connectionSecurityType) {
            case ELYTRON:
                complexXaDs = "complexXaDsWithElytron";
                break;
            case ELYTRON_AUTHENTICATION_CONTEXT:
                complexXaDs = "complexXaDsWithElytronCtxt";
                break;
            case SECURITY_DOMAIN:
                complexXaDs = "complexXaDs";
                break;
            case USER_PASSWORD:
                complexXaDs = "complexXaDsWithUserName";
                break;
            default:
                throw new InvalidParameterException("Unsupported connection security type in data sources: " +
                connectionSecurityType);
        }

        final String complexXaDsJndi = "java:jboss/xa-datasources/" + complexXaDs;

        final ModelNode address = new ModelNode();
        address.add("subsystem", "datasources");
        address.add("xa-data-source", complexXaDs);
        address.protect();

        final ModelNode operation = new ModelNode();
        operation.get(OP).set("add");
        operation.get(OP_ADDR).set(address);
        operation.get("enabled").set(false);

        Properties params = xaDsProperties(complexXaDsJndi, connectionSecurityType);
        setOperationParams(operation, params);
        addExtensionProperties(operation);
        operation.get("recovery-plugin-properties", "name").set("Property5");
        operation.get("recovery-plugin-properties", "name1").set("Property6");


        executeOperation(operation);

        final ModelNode xaDatasourcePropertiesAddress = address.clone();
        xaDatasourcePropertiesAddress.add("xa-datasource-properties", "URL");
        xaDatasourcePropertiesAddress.protect();
        final ModelNode xaDatasourcePropertyOperation = new ModelNode();
        xaDatasourcePropertyOperation.get(OP).set("add");
        xaDatasourcePropertyOperation.get(OP_ADDR).set(xaDatasourcePropertiesAddress);
        xaDatasourcePropertyOperation.get("value").set("jdbc:h2:mem:test");

        executeOperation(xaDatasourcePropertyOperation);

        List<ModelNode> newList = marshalAndReparseDsResources("xa-data-source");

        remove(address);

        Assertions.assertNotNull(newList, "Reparsing failed:");

        ModelNode rightChild = findNodeWithProperty(newList, "jndi-name", complexXaDsJndi);

        Assertions.assertTrue(checkModelParams(rightChild, params), "node:" + rightChild.asString() + ";\nparams" + params);

        Assertions.assertEquals("Property2", rightChild.get("valid-connection-checker-properties", "name").asString(), rightChild.asString());
        Assertions.assertEquals("Property4", rightChild.get("exception-sorter-properties", "name").asString(), rightChild.asString());
        Assertions.assertEquals("Property3", rightChild.get("stale-connection-checker-properties", "name").asString(), rightChild.asString());
        Assertions.assertEquals("Property1", rightChild.get("reauth-plugin-properties", "name").asString(), rightChild.asString());
        Assertions.assertEquals("Property5", rightChild.get("recovery-plugin-properties", "name").asString(), rightChild.asString());
        Assertions.assertEquals("Property6", rightChild.get("recovery-plugin-properties", "name1").asString(), rightChild.asString());

        Assertions.assertNotNull(findNodeWithProperty(newList, "value", "jdbc:h2:mem:test"), "xa-datasource-properties not propagated ");
    }

    private List<ModelNode> marshalAndReparseDsResources(String childType) throws Exception {
        DataSourcesExtension.DataSourceSubsystemParser parser = new DataSourcesExtension.DataSourceSubsystemParser();
        return xmlToModelOperations(modelToXml("datasources", childType, parser), Namespace.CURRENT.getUriString(), parser);
    }
}
