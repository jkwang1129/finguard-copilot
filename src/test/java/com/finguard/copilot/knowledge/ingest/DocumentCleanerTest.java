package com.finguard.copilot.knowledge.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentCleanerTest {

    @Test
    void canonicalizesBomLineEndingsAndUnicodeWhitespace() {
        CanonicalDocument document = new DocumentCleaner().canonicalize(
                "\uFEFF  Risk\u00A0rule\r\n\r\n  Amount\tthreshold  \r");

        assertThat(document.text()).isEqualTo("Risk rule\n\nAmount threshold");
        assertThat(document.checksum()).isEqualTo(
                "24ae026f43f0068d437c6b0d0c43eb85a5a97116443c22541a67929a264e5fc6");
    }

    @Test
    void rejectsBlankRawDocuments() {
        assertThatThrownBy(() -> new DocumentCleaner().canonicalize("\uFEFF \t\r\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawText");
    }

    @Test
    void canonicalChecksumIsStableForEquivalentLineEndings() {
        DocumentCleaner cleaner = new DocumentCleaner();

        assertThat(cleaner.canonicalize("Risk\r\nrule").checksum())
                .isEqualTo(cleaner.canonicalize("Risk\nrule").checksum());
    }
}
