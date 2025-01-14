package io.vertx.ext.web.handler.graphql;

import graphql.GraphQL;
import graphql.schema.*;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.WebTestBase;
import io.vertx.ext.web.handler.BodyHandler;
import org.junit.Test;

import java.util.List;

class Result {
  private final String id;

  Result(final String id) {
    this.id = id;
  }
}

public class MultipartRequestTest extends WebTestBase {
  @Override
  public void setUp() throws Exception {
    super.setUp();
    GraphQLHandler graphQLHandler = GraphQLHandler.create(graphQL(), createOptions());
    router.route().handler(BodyHandler.create());
    router.route("/graphql").order(100).handler(graphQLHandler);
  }

  private GraphQLHandlerOptions createOptions() {
    return new GraphQLHandlerOptions().setRequestMultipartEnabled(true)
      .setRequestBatchingEnabled(true);
  }

  private GraphQL graphQL() {
    final String schema = vertx.fileSystem().readFileBlocking("upload.graphqls").toString();
    final String emptyQueryschema = vertx.fileSystem().readFileBlocking("emptyQuery.graphqls").toString();

    final SchemaParser schemaParser = new SchemaParser();
    final TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(schema)
      .merge(schemaParser.parse(emptyQueryschema));

    final RuntimeWiring runtimeWiring = RuntimeWiring.newRuntimeWiring()
      .scalar(UploadScalar.build())
      .type("Mutation", builder -> {
        builder.dataFetcher("singleUpload", this::singleUpload);
        builder.dataFetcher("multipleUpload", this::multipleUpload);
        return builder;
      }).build();

    final SchemaGenerator schemaGenerator = new SchemaGenerator();
    final GraphQLSchema graphQLSchema = schemaGenerator
      .makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

    return GraphQL.newGraphQL(graphQLSchema).build();
  }

  @Test
  public void testSingleUploadMutation() {
    final HttpClient client = vertx.createHttpClient(getHttpClientOptions());

    final Buffer bodyBuffer = vertx.fileSystem().readFileBlocking("singleUpload.txt");

    client.request(new RequestOptions()
      .setMethod(HttpMethod.POST)
      .setURI("/graphql")
      .setIdleTimeout(10000)
      .addHeader("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundaryBpwmk50wSJmsTPAH")
      .addHeader("accept", "application/json"))
      .compose(req -> req.send(bodyBuffer)
        .compose(HttpClientResponse::body)
      ).onComplete(onSuccess(buffer -> {
      final JsonObject json = ((JsonObject) buffer.toJson()).getJsonObject("data").getJsonObject("singleUpload");
      assertEquals("a.txt", json.getString("id"));
      complete();
    }));

    await();
  }

  @Test
  public void testMultipleUploadMutation() {
    final HttpClient client = vertx.createHttpClient(getHttpClientOptions());

    final Buffer bodyBuffer = vertx.fileSystem().readFileBlocking("multipleUpload.txt");
    client.request(new RequestOptions()
      .setMethod(HttpMethod.POST)
      .setURI("/graphql")
      .addHeader("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundaryhvb6BzAACEqQKt0Z")
      .addHeader("accept", "application/json")
      .setIdleTimeout(10000))
      .compose(req -> req.send(bodyBuffer).compose(HttpClientResponse::body))
      .onComplete(onSuccess(buffer -> {
        final JsonObject json = ((JsonObject) buffer.toJson()).getJsonObject("data").getJsonObject("multipleUpload");
        assertEquals("b.txt c.txt", json.getString("id"));
        complete();
      }));

    await();
  }

  @Test
  public void testBatchUploadMutation() {
    final HttpClient client = vertx.createHttpClient(getHttpClientOptions());

    final Buffer bodyBuffer = vertx.fileSystem().readFileBlocking("batchUpload.txt");
    client.request(new RequestOptions()
        .setMethod(HttpMethod.POST)
        .setURI("/graphql")
        .addHeader("Content-Type", "multipart/form-data; boundary=------------------------560b6209af099a26")
        .addHeader("accept", "application/json")
        .setIdleTimeout(10000))
      .compose(req -> req.send(bodyBuffer).compose(HttpClientResponse::body))
      .onComplete(onSuccess(buffer -> {
      final JsonObject result = new JsonObject("{ \"array\":" + buffer.toString() + "}");
      assertEquals("a.txt", result.getJsonArray("array")
        .getJsonObject(0).getJsonObject("data")
        .getJsonObject("singleUpload").getString("id")
      );

      assertEquals("b.txt c.txt", result.getJsonArray("array")
        .getJsonObject(1).getJsonObject("data")
        .getJsonObject("multipleUpload").getString("id")
      );

      complete();
      }));

    await();
  }

  private Object singleUpload(DataFetchingEnvironment env) {
    final FileUpload file = env.getArgument("file");
    return new Result(file.fileName());
  }

  private Object multipleUpload(DataFetchingEnvironment env) {
    final List<FileUpload> files = env.getArgument("files");
    return new Result(files.get(0).fileName() + " " + files.get(1).fileName());
  }
}

