/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.iterative.rule.test;

import com.facebook.presto.Session;
import com.facebook.presto.connector.ConnectorId;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.testing.LocalQueryRunner;
import com.facebook.presto.tpch.TpchConnectorFactory;
import com.google.common.collect.ImmutableMap;

import java.io.Closeable;
import java.util.Map;

import static com.facebook.presto.testing.TestingSession.testSessionBuilder;

public class RuleTester
        implements Closeable
{
    private final Metadata metadata;
    private final Session session;
    private final LocalQueryRunner queryRunner;

    public RuleTester()
    {
        this(ImmutableMap.of());
    }

    public RuleTester(Map<String, String> sessionProperties)
    {
        Session.SessionBuilder sessionBuilder = testSessionBuilder()
                .setCatalog("local")
                .setSchema("tiny")
                .setSystemProperty("task_concurrency", "1"); // these tests don't handle exchanges from local parallel

        sessionProperties.forEach(sessionBuilder::setSystemProperty);

        session = sessionBuilder.build();

        queryRunner = new LocalQueryRunner(session);
        queryRunner.createCatalog(session.getCatalog().get(),
                new TpchConnectorFactory(1),
                ImmutableMap.<String, String>of());

        this.metadata = queryRunner.getMetadata();
    }

    public RuleAssert assertThat(Rule rule)
    {
        return new RuleAssert(metadata, session, queryRunner.getTransactionManager(), queryRunner.getAccessControl(), rule);
    }

    public Metadata getMetadata()
    {
        return metadata;
    }

    public ConnectorId getCurrentConnectorId()
    {
        return queryRunner.inTransaction(transactionSession -> metadata.getCatalogHandle(transactionSession, session.getCatalog().get())).get();
    }

    @Override
    public void close()
    {
        queryRunner.close();
    }
}
