package com.chavaillaz.jakarta.persistence.repository;

import static org.slf4j.LoggerFactory.getLogger;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import lombok.SneakyThrows;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;

import com.chavaillaz.jakarta.persistence.Identifiable;

/**
 * Base class of the tests running against a real Hibernate session factory, backed by an in memory database.
 * <p>
 * The subclasses declare the entity types they need in their own {@code @BeforeAll}, by calling
 * {@link #setupSessionFactory(Class...)}; the data is truncated after each test so that the tests stay isolated
 * without paying for a new session factory each time.
 */
public abstract class HibernateTest {

    private static final Logger log = getLogger(HibernateTest.class);

    protected static SessionFactory sessionFactory;

    protected Session session;

    /**
     * Setups the session factory for the given entity types, closing the previous one if any, so that a suite
     * mixing several sets of entities cannot leak a factory.
     *
     * @param types The entity types to map
     */
    protected static void setupSessionFactory(Class<?>... types) {
        cleanAll();

        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .loadProperties("hibernate.properties")
                .build();

        MetadataSources sources = new MetadataSources(registry);
        Stream.of(types).forEach(sources::addAnnotatedClass);

        Metadata metadata = sources.getMetadataBuilder().build();
        sessionFactory = metadata.getSessionFactoryBuilder()
                .applyAutoFlushing(true)
                .build();

        log.debug("Session factory started for {}", Stream.of(types).map(Class::getSimpleName).toList());
    }

    @AfterAll
    public static void cleanAll() {
        if (sessionFactory != null && !sessionFactory.isClosed()) {
            sessionFactory.close();
        }
        sessionFactory = null;
    }

    /**
     * @return The statistics of the session factory, to assert on the number of executed queries
     */
    protected static Statistics statistics() {
        return sessionFactory.getStatistics();
    }

    @BeforeEach
    public void setupCurrent() {
        session = sessionFactory.openSession();
        statistics().clear();
    }

    @AfterEach
    public void cleanCurrent() {
        if (session != null && session.isOpen()) {
            session.close();
        }
        deleteAll();
    }

    /**
     * Runs the given action in a transaction, rolling back and rethrowing when it fails, so that a failing test
     * can never leave a transaction open on the shared database.
     *
     * @param action The action to execute
     */
    protected void runInTransaction(Consumer<EntityManager> action) {
        inTransaction(entityManager -> {
            action.accept(entityManager);
            return null;
        });
    }

    /**
     * Runs the given function in a transaction and returns its result, detached from the closed persistence
     * context.
     *
     * @param <T>      The type of the result
     * @param function The function to execute
     * @return The result of the function
     */
    protected <T> T inTransaction(Function<EntityManager, T> function) {
        log.debug("Starting transaction");
        EntityManager entityManager = sessionFactory.createEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();
        transaction.begin();
        try {
            T result = function.apply(entityManager);
            if (transaction.getRollbackOnly()) {
                log.debug("Rolling back transaction");
                transaction.rollback();
            } else {
                log.debug("Committing transaction");
                transaction.commit();
            }
            return result;
        } catch (RuntimeException e) {
            if (transaction.isActive()) {
                log.debug("Rolling back transaction after {}", e.toString());
                transaction.rollback();
            }
            throw e;
        } finally {
            entityManager.close();
        }
    }

    /**
     * Persists the given entities in their own transaction.
     *
     * @param entities The entities to persist
     */
    protected void persist(Object... entities) {
        runInTransaction(entityManager -> Stream.of(entities).forEach(entityManager::persist));
    }

    /**
     * Deletes every row of every mapped entity, the referential integrity being disabled so that the deletion
     * order does not matter.
     */
    protected void deleteAll() {
        if (sessionFactory == null || sessionFactory.isClosed()) {
            return;
        }
        runInTransaction(entityManager -> {
            entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
            entityNames().forEach(name -> entityManager.createQuery("delete from " + name).executeUpdate());
            entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        });
    }

    /**
     * @return The names of the mapped entities
     */
    protected List<String> entityNames() {
        return sessionFactory.getMetamodel().getEntities().stream()
                .map(EntityType::getName)
                .toList();
    }

    protected <T, R extends Repository<E, ?>, E extends Identifiable<?>> T withRepository(Class<R> repositoryType, Function<R, T> action) {
        return inTransaction(entityManager -> action.apply(createRepo(repositoryType, entityManager)));

    }

    @SneakyThrows
    protected <R extends Repository<E, ?>, E extends Identifiable<?>> R createRepo(Class<R> repositoryType, EntityManager entityManager) {
        Constructor<R> ctor = repositoryType.getDeclaredConstructor(EntityManager.class);
        return ctor.newInstance(entityManager);
    }

}