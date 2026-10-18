package com.newtonite.ops.common;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Teaches Hibernate's query language (HQL) the PostgreSQL full-text match, which JPA has no syntax for.
 *
 * <p>{@code fts(w.searchVector, :q)} in HQL becomes
 * {@code (search_vector @@ websearch_to_tsquery('english', ?))} in SQL, which uses the GIN index.
 * Registered through {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}.
 */
public class PostgresFunctions implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        contributions.getFunctionRegistry().registerPattern(
                "fts",
                "(?1 @@ websearch_to_tsquery('english', ?2))",
                contributions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.BOOLEAN));
    }
}
