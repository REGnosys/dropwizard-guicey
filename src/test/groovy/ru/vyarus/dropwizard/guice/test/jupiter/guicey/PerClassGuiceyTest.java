package ru.vyarus.dropwizard.guice.test.jupiter.guicey;

import io.dropwizard.core.Application;
import io.dropwizard.core.Configuration;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import ru.vyarus.dropwizard.guice.GuiceBundle;
import ru.vyarus.dropwizard.guice.module.GuiceyConfigurationInfo;
import ru.vyarus.dropwizard.guice.test.jupiter.TestGuiceyApp;

import javax.inject.Inject;

/**
 * @author Vyacheslav Rusakov
 * @since 13.10.2021
 */
@TestGuiceyApp(PerClassGuiceyTest.App.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PerClassGuiceyTest {

    @Inject
    GuiceyConfigurationInfo info;

    @Test
    public void testInject() {
        Assertions.assertNotNull(info);
    }

    @Nested
    public class Nest {

        @Inject
        Environment environment;

        @Test
        public void testInject() {
            Assertions.assertNotNull(environment);
        }

    }

    public static class App extends Application<Configuration> {

        @Override
        public void initialize(Bootstrap<Configuration> bootstrap) {
            bootstrap.addBundle(GuiceBundle.builder().build());
        }

        @Override
        public void run(Configuration configuration, Environment environment) throws Exception {
        }
    }
}
