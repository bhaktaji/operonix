package com.operonix.document;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "document_sequence")
@Getter
@Setter
@NoArgsConstructor
public class DocumentSequence {

    @EmbeddedId
    private DocumentSequenceId id;

    @Column(name = "next_value", nullable = false)
    private int nextValue;

    public DocumentSequence(DocumentSequenceId id, int nextValue) {
        this.id = id;
        this.nextValue = nextValue;
    }
}
