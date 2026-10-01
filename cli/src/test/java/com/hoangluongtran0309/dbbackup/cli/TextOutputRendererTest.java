package com.hoangluongtran0309.dbbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class TextOutputRendererTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rendersObjectCollectionsAsPlainTables() throws Exception {
        JsonNode data = json.readTree("""
                [
                  {"id":"1","name":"production","engine":"POSTGRESQL","lastConnectionCheck":null},
                  {"id":"2","name":"archive","engine":"SQLITE","lastConnectionCheck":{"successful":true}}
                ]
                """);

        String rendered = TextOutputRenderer.render(data);

        assertThat(rendered)
                .contains("ID", "NAME", "ENGINE", "production", "POSTGRESQL", "archive", "SQLITE")
                .doesNotContain("{", "}", "[", "]", "lastConnectionCheck");
    }

    @Test
    void rendersDetailsNestedTablesPaginationAndNulls() throws Exception {
        JsonNode data = json.readTree("""
                {
                  "page": 1,
                  "hasOlder": false,
                  "items": [{"id":"backup-1","status":"SUCCEEDED","errorMessage":null}],
                  "summary": {"count":1,"latest":null}
                }
                """);

        String rendered = TextOutputRenderer.render(data);

        assertThat(rendered).contains(
                "page: 1", "hasOlder: false", "items:", "ID", "STATUS", "backup-1", "SUCCEEDED",
                "ERROR MESSAGE", "-", "summary:", "count: 1", "latest: -");
    }

    @Test
    void rendersEmptyCollectionsExplicitly() throws Exception {
        assertThat(TextOutputRenderer.render(json.readTree("[]"))).isEqualTo("(none)");
    }

    @Test
    void rendersEveryValidationErrorForTextMode() throws Exception {
        JsonNode envelope = json.readTree("""
                {"ok":false,"error":{"code":"VALIDATION_ERROR","message":"Name is required","field":"name",
                  "errors":[
                    {"field":"name","message":"Name is required"},
                    {"field":"host","message":"Host is required"}
                  ]}}
                """);

        assertThat(TextOutputRenderer.renderError(envelope, "fallback"))
                .isEqualTo("""
                        Validation failed:
                          name: Name is required
                          host: Host is required""");
    }
}
