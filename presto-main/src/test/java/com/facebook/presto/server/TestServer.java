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
package com.facebook.presto.server;

import com.facebook.airlift.http.client.FullJsonResponseHandler.JsonResponse;
import com.facebook.airlift.http.client.HttpClient;
import com.facebook.airlift.http.client.HttpUriBuilder;
import com.facebook.airlift.http.client.Request;
import com.facebook.airlift.http.client.StatusResponseHandler;
import com.facebook.airlift.http.client.UnexpectedResponseException;
import com.facebook.airlift.http.client.jetty.JettyHttpClient;
import com.facebook.airlift.json.JsonCodec;
import com.facebook.airlift.testing.Closeables;
import com.facebook.presto.CompressionCodec;
import com.facebook.presto.client.QueryError;
import com.facebook.presto.client.QueryResults;
import com.facebook.presto.common.Page;
import com.facebook.presto.common.block.BlockEncodingManager;
import com.facebook.presto.common.type.TimeZoneNotSupportedException;
import com.facebook.presto.execution.QueryIdGenerator;
import com.facebook.presto.execution.buffer.PagesSerdeFactory;
import com.facebook.presto.server.testing.TestingPrestoServer;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.function.SqlFunctionId;
import com.facebook.presto.spi.function.SqlInvokedFunction;
import com.facebook.presto.spi.page.PagesSerde;
import com.facebook.presto.spi.page.SerializedPage;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.BasicSliceInput;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.URI;
import java.util.Base64;
import java.util.List;

import static com.facebook.airlift.http.client.FullJsonResponseHandler.createFullJsonResponseHandler;
import static com.facebook.airlift.http.client.JsonResponseHandler.createJsonResponseHandler;
import static com.facebook.airlift.http.client.Request.Builder.fromRequest;
import static com.facebook.airlift.http.client.Request.Builder.prepareGet;
import static com.facebook.airlift.http.client.Request.Builder.prepareHead;
import static com.facebook.airlift.http.client.Request.Builder.preparePost;
import static com.facebook.airlift.http.client.Request.Builder.preparePut;
import static com.facebook.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static com.facebook.airlift.http.client.StatusResponseHandler.createStatusResponseHandler;
import static com.facebook.airlift.json.JsonCodec.jsonCodec;
import static com.facebook.presto.SystemSessionProperties.HASH_PARTITION_COUNT;
import static com.facebook.presto.SystemSessionProperties.JOIN_DISTRIBUTION_TYPE;
import static com.facebook.presto.SystemSessionProperties.QUERY_MAX_MEMORY;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_CATALOG;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_CLIENT_INFO;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_PREPARED_STATEMENT;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SCHEMA;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SESSION;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SESSION_FUNCTION;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SOURCE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_STARTED_TRANSACTION_ID;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_TIME_ZONE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_TRANSACTION_ID;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_USER;
import static com.facebook.presto.common.type.VarcharType.VARCHAR;
import static com.facebook.presto.server.TestHttpRequestSessionContext.createFunctionAdd;
import static com.facebook.presto.server.TestHttpRequestSessionContext.createSqlFunctionIdAdd;
import static com.facebook.presto.server.TestHttpRequestSessionContext.urlEncode;
import static com.facebook.presto.spi.StandardErrorCode.INCOMPATIBLE_CLIENT;
import static com.facebook.presto.spi.page.PagesSerdeUtil.readSerializedPage;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.ws.rs.core.HttpHeaders.CACHE_CONTROL;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.OK;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestServer
{
    private static final JsonCodec<QueryResults> QUERY_RESULTS_CODEC = jsonCodec(QueryResults.class);

    private static final SqlFunctionId SQL_FUNCTION_ID_ADD = createSqlFunctionIdAdd();
    private static final SqlInvokedFunction SQL_FUNCTION_ADD = createFunctionAdd();
    private static final String SERIALIZED_SQL_FUNCTION_ID_ADD = jsonCodec(SqlFunctionId.class).toJson(SQL_FUNCTION_ID_ADD);
    private static final String SERIALIZED_SQL_FUNCTION_ADD = jsonCodec(SqlInvokedFunction.class).toJson(SQL_FUNCTION_ADD);

    private TestingPrestoServer server;
    private HttpClient client;

    @BeforeMethod
    public void setup()
            throws Exception
    {
        server = new TestingPrestoServer();
        client = new JettyHttpClient();
    }

    @AfterMethod
    public void teardown()
    {
        Closeables.closeQuietly(server);
        Closeables.closeQuietly(client);
    }

    @Test
    public void testInvalidSessionError()
    {
        String invalidTimeZone = "this_is_an_invalid_time_zone";
        Request request = preparePost().setHeader(PRESTO_USER, "user")
                .setUri(uriFor("/v1/statement"))
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_CATALOG, "catalog")
                .setHeader(PRESTO_SCHEMA, "schema")
                .setHeader(PRESTO_TIME_ZONE, invalidTimeZone)
                .build();

        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));
        while (queryResults.getNextUri() != null) {
            queryResults = client.execute(prepareGet().setUri(queryResults.getNextUri()).build(), createJsonResponseHandler(QUERY_RESULTS_CODEC));
        }
        QueryError queryError = queryResults.getError();
        assertNotNull(queryError);

        TimeZoneNotSupportedException expected = new TimeZoneNotSupportedException(invalidTimeZone);
        assertEquals(queryError.getMessage(), expected.getMessage());
    }

    @Test
    public void testServerStarts()
    {
        StatusResponseHandler.StatusResponse response = client.execute(
                prepareGet().setUri(server.resolve("/v1/query")).build(),
                createStatusResponseHandler());

        assertEquals(response.getStatusCode(), OK.getStatusCode());
    }

    @Test
    public void testBinaryResults()
    {
        // start query
        URI uri = HttpUriBuilder.uriBuilderFrom(server.getBaseUrl())
                .replacePath("/v1/statement")
                .replaceParameter("binaryResults", "true")
                .build();
        Request request = preparePost()
                .setUri(uri)
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_CATALOG, "catalog")
                .setHeader(PRESTO_SCHEMA, "schema")
                .setHeader(PRESTO_CLIENT_INFO, "{\"clientVersion\":\"testVersion\"}")
                .build();

        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));

        ImmutableList.Builder<Object> data = ImmutableList.builder();
        while (queryResults.getNextUri() != null) {
            Request nextRequest = prepareGet()
                    .setUri(queryResults.getNextUri())
                    .build();
            queryResults = client.execute(nextRequest, createJsonResponseHandler(QUERY_RESULTS_CODEC));

            assertNull(queryResults.getData());
            if (queryResults.getBinaryData() != null) {
                data.addAll(queryResults.getBinaryData());
            }
        }

        if (queryResults.getError() != null) {
            fail(queryResults.getError().toString());
        }

        List<Object> encodedPages = data.build();

        assertEquals(1, encodedPages.size());
        byte[] decodedPage = Base64.getDecoder().decode((String) encodedPages.get(0));

        BlockEncodingManager blockEncodingSerde = new BlockEncodingManager();
        PagesSerde pagesSerde = new PagesSerdeFactory(blockEncodingSerde, CompressionCodec.NONE, false).createPagesSerde();
        BasicSliceInput pageInput = new BasicSliceInput(Slices.wrappedBuffer(decodedPage, 0, decodedPage.length));
        SerializedPage serializedPage = readSerializedPage(pageInput);

        Page page = pagesSerde.deserialize(serializedPage);

        assertEquals(1, page.getChannelCount());
        assertEquals(1, page.getPositionCount());

        // only the system catalog exists by default
        Slice slice = VARCHAR.getSlice(page.getBlock(0), 0);
        assertEquals(slice.toStringUtf8(), "system");
    }

    @Test
    public void testQuery()
    {
        // start query
        Request request = preparePost()
                .setUri(uriFor("/v1/statement"))
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_CATALOG, "catalog")
                .setHeader(PRESTO_SCHEMA, "schema")
                .setHeader(PRESTO_CLIENT_INFO, "{\"clientVersion\":\"testVersion\"}")
                .addHeader(PRESTO_SESSION, QUERY_MAX_MEMORY + "=1GB")
                .addHeader(PRESTO_SESSION, JOIN_DISTRIBUTION_TYPE + "=partitioned," + HASH_PARTITION_COUNT + " = 43")
                .addHeader(PRESTO_PREPARED_STATEMENT, "foo=select * from bar")
                .addHeader(PRESTO_SESSION_FUNCTION, format("%s=%s", urlEncode(SERIALIZED_SQL_FUNCTION_ID_ADD), urlEncode(SERIALIZED_SQL_FUNCTION_ADD)))
                .build();

        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));

        ImmutableList.Builder<List<Object>> data = ImmutableList.builder();
        while (queryResults.getNextUri() != null) {
            queryResults = client.execute(prepareGet().setUri(queryResults.getNextUri()).build(), createJsonResponseHandler(QUERY_RESULTS_CODEC));

            if (queryResults.getData() != null) {
                data.addAll(queryResults.getData());
            }
        }

        if (queryResults.getError() != null) {
            fail(queryResults.getError().toString());
        }

        // get the query info
        BasicQueryInfo queryInfo = server.getQueryManager().getQueryInfo(new QueryId(queryResults.getId()));

        // verify session properties
        assertEquals(queryInfo.getSession().getSystemProperties(), ImmutableMap.builder()
                .put(QUERY_MAX_MEMORY, "1GB")
                .put(JOIN_DISTRIBUTION_TYPE, "partitioned")
                .put(HASH_PARTITION_COUNT, "43")
                .build());

        // verify client info in session
        assertEquals(queryInfo.getSession().getClientInfo().get(), "{\"clientVersion\":\"testVersion\"}");

        // verify prepared statements
        assertEquals(queryInfo.getSession().getPreparedStatements(), ImmutableMap.builder()
                .put("foo", "select * from bar")
                .build());

        // verify session functions
        assertEquals(queryInfo.getSession().getSessionFunctions(), ImmutableMap.of(SQL_FUNCTION_ID_ADD, SQL_FUNCTION_ADD));

        // only the system catalog exists by default
        List<List<Object>> rows = data.build();
        assertEquals(rows, ImmutableList.of(ImmutableList.of("system")));
    }

    @Test
    public void testQueryWithPreMintedQueryIdAndSlug()
    {
        QueryId queryId = new QueryIdGenerator().createNextQueryId();
        String slug = "xxx";
        Request request = preparePut()
                .setUri(uriFor("/v1/statement/", queryId, slug))
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_CATALOG, "catalog")
                .setHeader(PRESTO_SCHEMA, "schema")
                .build();

        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));

        // verify slug in nextUri is same as requested
        assertEquals(queryResults.getNextUri().getQuery(), "slug=xxx");

        // verify nextUri points to requested query id
        assertEquals(queryResults.getNextUri().getPath(), format("/v1/statement/queued/%s/1", queryId));

        while (queryResults.getNextUri() != null) {
            queryResults = client.execute(prepareGet().setUri(queryResults.getNextUri()).build(), createJsonResponseHandler(QUERY_RESULTS_CODEC));
        }

        if (queryResults.getError() != null) {
            fail(queryResults.getError().toString());
        }

        // verify query id was passed down properly
        assertEquals(server.getDispatchManager().getQueryInfo(queryId).getQueryId(), queryId);
    }

    @Test
    public void testPutStatementIdempotency()
    {
        QueryId queryId = new QueryIdGenerator().createNextQueryId();
        Request request = preparePut()
                .setUri(uriFor("/v1/statement/", queryId, "slug"))
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_CATALOG, "catalog")
                .setHeader(PRESTO_SCHEMA, "schema")
                .build();

        client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));
        // Execute PUT request again should succeed
        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));

        while (queryResults.getNextUri() != null) {
            queryResults = client.execute(prepareGet().setUri(queryResults.getNextUri()).build(), createJsonResponseHandler(QUERY_RESULTS_CODEC));
        }
        if (queryResults.getError() != null) {
            fail(queryResults.getError().toString());
        }
    }

    @Test(expectedExceptions = UnexpectedResponseException.class, expectedExceptionsMessageRegExp = "Expected response code to be \\[.*\\], but was 409")
    public void testPutStatementWithDifferentSlugFails()
    {
        QueryId queryId = new QueryIdGenerator().createNextQueryId();
        Request request = preparePut()
                .setUri(uriFor("/v1/statement/", queryId, "slug"))
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_CATALOG, "catalog")
                .setHeader(PRESTO_SCHEMA, "schema")
                .build();
        client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));

        Request badRequest = fromRequest(request)
                .setUri(uriFor("/v1/statement/", queryId, "different_slug"))
                .build();
        client.execute(badRequest, createJsonResponseHandler(QUERY_RESULTS_CODEC));
    }

    @Test(expectedExceptions = UnexpectedResponseException.class, expectedExceptionsMessageRegExp = "Expected response code to be \\[.*\\], but was 409")
    public void testPutStatementAfterGetFails()
    {
        QueryId queryId = new QueryIdGenerator().createNextQueryId();
        Request request = preparePut()
                .setUri(uriFor("/v1/statement/", queryId, "slug"))
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_CATALOG, "catalog")
                .setHeader(PRESTO_SCHEMA, "schema")
                .build();

        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));
        client.execute(prepareGet().setUri(queryResults.getNextUri()).build(), createJsonResponseHandler(QUERY_RESULTS_CODEC));
        client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));
    }

    @Test
    public void testTransactionSupport()
    {
        Request request = preparePost()
                .setUri(uriFor("/v1/statement"))
                .setBodyGenerator(createStaticBodyGenerator("start transaction", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .setHeader(PRESTO_TRANSACTION_ID, "none")
                .build();

        JsonResponse<QueryResults> queryResults = client.execute(request, createFullJsonResponseHandler(QUERY_RESULTS_CODEC));
        ImmutableList.Builder<List<Object>> data = ImmutableList.builder();
        while (true) {
            if (queryResults.getValue().getData() != null) {
                data.addAll(queryResults.getValue().getData());
            }

            if (queryResults.getValue().getNextUri() == null) {
                break;
            }
            queryResults = client.execute(prepareGet().setUri(queryResults.getValue().getNextUri()).build(), createFullJsonResponseHandler(QUERY_RESULTS_CODEC));
        }

        if (queryResults.getValue().getError() != null) {
            fail(queryResults.getValue().getError().toString());
        }
        assertNotNull(queryResults.getHeader(PRESTO_STARTED_TRANSACTION_ID));
    }

    @Test
    public void testNoTransactionSupport()
    {
        Request request = preparePost()
                .setUri(uriFor("/v1/statement"))
                .setBodyGenerator(createStaticBodyGenerator("start transaction", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .setHeader(PRESTO_SOURCE, "source")
                .build();

        QueryResults queryResults = client.execute(request, createJsonResponseHandler(QUERY_RESULTS_CODEC));
        while (queryResults.getNextUri() != null) {
            queryResults = client.execute(prepareGet().setUri(queryResults.getNextUri()).build(), createJsonResponseHandler(QUERY_RESULTS_CODEC));
        }

        assertNotNull(queryResults.getError());
        assertEquals(queryResults.getError().getErrorCode(), INCOMPATIBLE_CLIENT.toErrorCode().getCode());
    }

    @Test
    public void testStatusPing()
    {
        Request request = prepareHead()
                .setUri(uriFor("/v1/status"))
                .setFollowRedirects(false)
                .build();
        StatusResponseHandler.StatusResponse response = client.execute(request, createStatusResponseHandler());
        assertEquals(response.getStatusCode(), OK.getStatusCode(), "Status code");
        assertEquals(response.getHeader(CONTENT_TYPE), APPLICATION_JSON, "Content Type");
    }

    @Test
    public void testCacheControlHeaderExists()
    {
        Request request = preparePost()
                .setUri(uriFor("/v1/statement"))
                .setBodyGenerator(createStaticBodyGenerator("show catalogs", UTF_8))
                .setHeader(PRESTO_USER, "user")
                .build();

        JsonResponse<QueryResults> initResponse = client.execute(request, createFullJsonResponseHandler(QUERY_RESULTS_CODEC));

        String initHeader = initResponse.getHeader(CACHE_CONTROL);
        assertNotNull(initHeader);
        assertTrue(initHeader.contains("max-age"));

        int initAge = parseInt(initHeader.substring(initHeader.indexOf("=") + 1));
        assertTrue(initAge >= 0);

        JsonResponse<QueryResults> queryResults = initResponse;
        while (queryResults.getValue().getNextUri() != null) {
            URI nextUri = queryResults.getValue().getNextUri();
            queryResults = client.execute(prepareGet().setUri(nextUri).build(), createFullJsonResponseHandler(QUERY_RESULTS_CODEC));

            String header = queryResults.getHeader(CACHE_CONTROL);
            assertNotNull(header);
            assertTrue(header.contains("max-age"));

            int maxAge = parseInt(header.substring(header.indexOf("=") + 1));
            assertTrue(maxAge >= 0);
        }
    }

    public URI uriFor(String path)
    {
        return HttpUriBuilder.uriBuilderFrom(server.getBaseUrl()).replacePath(path).build();
    }

    public URI uriFor(String path, QueryId queryId, String slug)
    {
        return HttpUriBuilder.uriBuilderFrom(server.getBaseUrl())
                .replacePath(path)
                .appendPath(queryId.getId())
                .addParameter("slug", slug)
                .build();
    }
}
