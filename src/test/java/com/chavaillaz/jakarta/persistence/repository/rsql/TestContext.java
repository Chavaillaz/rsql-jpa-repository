package com.chavaillaz.jakarta.persistence.repository.rsql;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.chavaillaz.jakarta.persistence.repository.CursorCodec;
import com.chavaillaz.jakarta.persistence.repository.CursorKeyCodec;
import com.chavaillaz.jakarta.persistence.repository.RepositoryContext;

/**
 * The context a repository hands over to the query collaborators, assembled by hand so that the RSQL support can be
 * exercised on its own, without a repository around it.
 *
 * @param <E>           The type of the managed entity
 * @param entityManager The entity manager the queries run on
 * @param defaults      The default ordering of the repository
 * @param properties    The properties that can be sorted and filtered on
 */
record TestContext<E>(
        EntityManager entityManager,
        BiFunction<CriteriaBuilder, Root<E>, List<Order>> defaults,
        Map<String, String> properties) implements RepositoryContext<E> {

    @Override
    public List<Order> defaultOrders(CriteriaBuilder criteriaBuilder, Root<E> root) {
        return defaults.apply(criteriaBuilder, root);
    }

    @Override
    public Map<String, String> searchableProperties() {
        return properties;
    }

    @Override
    public CursorCodec cursorCodec() {
        return CursorCodec.DEFAULT;
    }

    @Override
    public CursorKeyCodec cursorKeyCodec() {
        return CursorKeyCodec.DEFAULT;
    }

}
