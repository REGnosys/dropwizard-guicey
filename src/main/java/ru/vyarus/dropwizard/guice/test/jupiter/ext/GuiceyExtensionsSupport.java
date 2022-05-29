package ru.vyarus.dropwizard.guice.test.jupiter.ext;

import com.google.common.base.Preconditions;
import com.google.inject.Injector;
import io.dropwizard.testing.DropwizardTestSupport;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.util.ReflectionUtils;
import ru.vyarus.dropwizard.guice.hook.ConfigurationHooksSupport;
import ru.vyarus.dropwizard.guice.hook.GuiceyConfigurationHook;
import ru.vyarus.dropwizard.guice.injector.lookup.InjectorLookup;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.EnableHook;
import ru.vyarus.dropwizard.guice.test.jupiter.env.EnableSetup;
import ru.vyarus.dropwizard.guice.test.jupiter.env.TestEnvironmentSetup;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.conf.TestExtensionsTracker;
import ru.vyarus.dropwizard.guice.test.util.HooksUtil;
import ru.vyarus.dropwizard.guice.test.util.TestSetupUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Base class for junit 5 extensions implementations. All extensions use {@link DropwizardTestSupport} object
 * for actual execution (only configuration differs).
 * <p>
 * Extensions assumed to be used only on class level: extension will start dropwizard app before all tests
 * and shut down it after all tests. If nested tests used - they also affected. Execution per test is not allowed
 * because these tests are integration tests and they must minimize environment preparation time. Group tests
 * not affecting application state into one class and use different test classes (or nested classes) for tests
 * modifying state.
 * <p>
 * Test instance is not managed by guice! Only {@link com.google.inject.Injector#injectMembers(Object)} applied
 * for it to process test fields injection. Guice AOP can't be used on test methods. Technically, creating test
 * instances with guice is possible, but in this case nested tests could not work at all, which is unacceptable.
 * <p>
 * Extension detects static fields of {@link GuiceyConfigurationHook} type, annotated with {@link EnableHook}
 * and initialize these hooks automatically. It was done like this to simplify customizations, when main extension
 * could be declared as annotation and hook as field. Also, it was impossible to implement hooks support
 * with junit extension. Hook field could be declared even in base test class.
 * <p>
 * For external integrations (other extensions), there is a special "hack" allowing to access
 * {@link DropwizardTestSupport} object (and so get access to injector): {@link #lookupSupport(ExtensionContext)}.
 * And shortcuts {@link #lookupInjector(ExtensionContext)} and {@link #lookupClient(ExtensionContext)}.
 *
 * @author Vyacheslav Rusakov
 * @see TestParametersSupport for supported test parameters
 * @since 29.04.2020
 */
public abstract class GuiceyExtensionsSupport extends TestParametersSupport implements
        BeforeAllCallback,
        AfterAllCallback,
        BeforeEachCallback {

    // dropwizard support storage key (store visible for all relative tests)
    private static final String DW_SUPPORT = "DW_SUPPORT";
    // ClientFactory instance
    private static final String DW_CLIENT = "DW_CLIENT";
    // indicator storage key of nested test (when extension activated in parent test)
    private static final String INHERITED_DW_SUPPORT = "INHERITED_DW_SUPPORT";

    protected final TestExtensionsTracker tracker;

    public GuiceyExtensionsSupport(final TestExtensionsTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public void beforeAll(final ExtensionContext context) throws Exception {
        final ExtensionContext.Store store = getExtensionStore(context);
        if (store.get(DW_SUPPORT) == null) {
            // find fields annotated with @EnableHook and @EnableSetup
            final FieldSupport fields = new FieldSupport(context.getRequiredTestClass(), tracker);
            fields.activateBaseHooks();

            final DropwizardTestSupport<?> support = prepareTestSupport(context, fields.getSetupObjects());
            // activate hooks declared in test static fields (so hooks declared in annotation goes before)
            fields.activateClassHooks();
            store.put(DW_SUPPORT, support);
            // for pure guicey tests client may seem redundant, but it can be used for calling other services
            store.put(DW_CLIENT, new ClientSupport(support));

            tracker.logExtensionRegistrations();

            support.before();
        } else {
            // in case of nested test, beforeAll for root extension will be called second time (because junit keeps
            // only one extension instance!) and this means we should not perform initialization, but we also must
            // prevent afterAll call for this nested test too and so need to store marker value!

            final ExtensionContext.Store localStore = getLocalExtensionStore(context);
            // just in case
            Preconditions.checkState(localStore.get(INHERITED_DW_SUPPORT) == null,
                    "Storage assumptions were wrong or unexpected junit usage appear. "
                            + "Please report this case to guicey developer.");
            localStore.put(INHERITED_DW_SUPPORT, true);
        }
    }

    @Override
    public void beforeEach(final ExtensionContext context) {
        // before each used to properly handle both default @TestInstance(TestInstance.Lifecycle.PER_METHOD)
        // and @TestInstance(TestInstance.Lifecycle.PER_CLASS) (in later case BeforeAllCallback called after
        // TestInstancePostProcessor, making it not usable for this task)

        final Object testInstance = context.getTestInstance()
                .orElseThrow(() -> new IllegalStateException("Unable to get the current test instance"));

        final DropwizardTestSupport<?> support = Preconditions.checkNotNull(getSupport(context),
                "Guicey test support was not initialized: most likely, you are trying to manually "
                        + "register extension using non-static field - such usage is not supported.");

        InjectorLookup.getInjector(support.getApplication()).orElseThrow(() ->
                        new IllegalStateException("Can't find guicey injector to process test fields injections"))
                .injectMembers(testInstance);
    }

    @Override
    public void afterAll(final ExtensionContext context) throws Exception {
        // just in case, normally hooks cleared automatically after appliance
        ConfigurationHooksSupport.reset();

        final ExtensionContext.Store localExtensionStore = getLocalExtensionStore(context);
        if (localExtensionStore.get(INHERITED_DW_SUPPORT) != null) {
            localExtensionStore.remove(INHERITED_DW_SUPPORT);
            // do nothing: extension managed on upper context
            return;
        }

        final DropwizardTestSupport<?> support = getSupport(context);
        if (support != null) {
            support.after();
        }
        final ClientSupport client = getClient(context);
        if (client != null) {
            client.close();
        }
    }

    // --------------------------------------------------------- 3rd party extensions support

    /**
     * Static "hack" for other extensions extending base guicey extensions abilities.
     * <p>
     * The only thin moment here is extensions order! Junit preserve declaration order so in most cases it
     * should not be a problem.
     *
     * @param extensionContext extension context
     * @return dropwizard support object prepared by guicey extension, or null if no guicey extension used or
     * its beforeAll hook was not called yet
     */
    public static Optional<DropwizardTestSupport<?>> lookupSupport(final ExtensionContext extensionContext) {
        return Optional.ofNullable((DropwizardTestSupport<?>) getExtensionStore(extensionContext).get(DW_SUPPORT));
    }

    /**
     * Shortcut for application injector resolution be used by other extensions.
     * <p>
     * Custom extension must be activated after main guicey extension!
     *
     * @param extensionContext extension context
     * @return application injector or null if not available
     */
    public static Optional<Injector> lookupInjector(final ExtensionContext extensionContext) {
        return lookupSupport(extensionContext).flatMap(it -> InjectorLookup.getInjector(it.getApplication()));
    }

    /**
     * Shortcut for {@link ClientSupport} object lookup by other extensions.
     * <p>
     * Custom extension must be activated after main guicey extension!
     *
     * @param extensionContext extension context
     * @return client factory object or null if not available
     */
    public static Optional<ClientSupport> lookupClient(final ExtensionContext extensionContext) {
        return Optional.ofNullable((ClientSupport) getExtensionStore(extensionContext).get(DW_CLIENT));
    }

    // --------------------------------------------------------- end of 3rd party extensions support

    /**
     * The only role of actual extension class is to configure {@link DropwizardTestSupport} object
     * according to annotation.
     *
     * @param context extension context
     * @param setups setup extensions resolved from fields (or empty list)
     * @return configured dropwizard test support object
     */
    protected abstract DropwizardTestSupport<?> prepareTestSupport(ExtensionContext context,
                                                                   List<TestEnvironmentSetup> setups);

    @Override
    protected DropwizardTestSupport<?> getSupport(final ExtensionContext extensionContext) {
        return lookupSupport(extensionContext).orElse(null);
    }

    @Override
    protected ClientSupport getClient(final ExtensionContext extensionContext) {
        return lookupClient(extensionContext).orElse(null);
    }

    @Override
    protected Optional<Injector> getInjector(final ExtensionContext extensionContext) {
        return lookupInjector(extensionContext);
    }

    protected static ExtensionContext.Store getExtensionStore(final ExtensionContext context) {
        // Store is extension specific, but nested tests will see it too (because key is extension class)
        return context.getStore(ExtensionContext.Namespace.create(GuiceyExtensionsSupport.class));
    }

    private ExtensionContext.Store getLocalExtensionStore(final ExtensionContext context) {
        // test scoped extension scope (required to differentiate nested classes or parameterized executions)
        return context.getStore(ExtensionContext.Namespace
                .create(GuiceyExtensionsSupport.class, context.getRequiredTestClass()));
    }

    /**
     * Utility class for activating hooks and setup objects collected from fields (annotated with
     * {@link EnableHook} and {link {@link ru.vyarus.dropwizard.guice.test.jupiter.env.EnableSetup}}).
     * <p>
     * Hook fields must be activated in two steps: first hooks declared in base classes, then hooks declared directly
     * in test class (after hooks declared in extension would be activated).
     * <p>
     * Setup extensions from fields are always registered after all other.
     */
    private static class FieldSupport {
        private final TestExtensionsTracker tracker;
        private final List<Field> parentHookFields;
        private final List<Field> ownHookFields;
        private final List<Field> extensionFields;

        FieldSupport(final Class<?> testClass, final TestExtensionsTracker tracker) {
            this.tracker = tracker;
            // find and validate all fields
            ownHookFields = findHookFields(testClass);
            parentHookFields = ownHookFields.isEmpty() ? Collections.emptyList() : ownHookFields.stream()
                    .filter(field -> !testClass.equals(field.getDeclaringClass()))
                    .collect(Collectors.toList());
            ownHookFields.removeAll(parentHookFields);

            extensionFields = findSetupFields(testClass);
        }

        public List<TestEnvironmentSetup> getSetupObjects() {
            tracker.extensionsFromFields(extensionFields);
            return extensionFields.isEmpty() ? Collections.emptyList() : getFieldValues(extensionFields);
        }

        public void activateBaseHooks() {
            // activate hooks declared in base classes
            activateFieldHooks(parentHookFields);
            tracker.hooksFromFields(parentHookFields, true);
        }

        public void activateClassHooks() {
            // activate all remaining hooks (in test class)
            activateFieldHooks(ownHookFields);
            tracker.hooksFromFields(ownHookFields, false);
        }

        private void activateFieldHooks(final List<Field> fields) {
            HooksUtil.register(getFieldValues(fields));
        }

        @SuppressWarnings("unchecked")
        private <T> List<T> getFieldValues(final List<Field> fields) {
            return fields.isEmpty() ? Collections.emptyList() : (List<T>)
                    ReflectionUtils.readFieldValues(fields, null);
        }

        private List<Field> findHookFields(final Class<?> testClass) {
            final List<Field> fields = AnnotationSupport.findAnnotatedFields(testClass, EnableHook.class);
            HooksUtil.validateFieldHooks(fields);
            return fields.isEmpty() ? Collections.emptyList() : new ArrayList<>(fields);
        }

        private List<Field> findSetupFields(final Class<?> testClass) {
            final List<Field> fields = AnnotationSupport.findAnnotatedFields(testClass, EnableSetup.class);
            TestSetupUtils.validateFields(fields);
            return fields.isEmpty() ? Collections.emptyList() : new ArrayList<>(fields);
        }
    }
}
