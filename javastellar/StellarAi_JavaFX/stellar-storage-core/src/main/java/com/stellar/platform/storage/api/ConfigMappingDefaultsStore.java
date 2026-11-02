package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.SemanticSignalMappingMetadata;

import java.util.List;

/**
 * Product-wide configuration mappings, shared by every project on this installation.
 *
 * <p>These are the fallback a project inherits when it has no mapping of its own: a fleet that always
 * names latitude the same way is configured once, not once per project. Rows carry no project and no
 * measurement source; resolution against a concrete catalog happens by signal name.
 */
public interface ConfigMappingDefaultsStore extends AutoCloseable {

    /** Stores (or replaces) the product default for one field of one configuration group. */
    void upsertDefaultMapping(SemanticSignalMappingMetadata mapping);

    List<SemanticSignalMappingMetadata> listDefaultMappings(String configGroup);

    /** Removes the product default for one field, leaving the other fields untouched. */
    void deleteDefaultMapping(String configGroup, String role);

    void deleteDefaultMappings(String configGroup);

    @Override
    void close();
}
