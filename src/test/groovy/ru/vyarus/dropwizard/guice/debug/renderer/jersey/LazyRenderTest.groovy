package ru.vyarus.dropwizard.guice.debug.renderer.jersey

import io.dropwizard.core.Application
import io.dropwizard.core.Configuration
import io.dropwizard.core.setup.Bootstrap
import io.dropwizard.core.setup.Environment
import org.glassfish.jersey.internal.inject.InjectionManager
import ru.vyarus.dropwizard.guice.GuiceBundle
import ru.vyarus.dropwizard.guice.bundle.lookup.PropertyBundleLookup
import ru.vyarus.dropwizard.guice.debug.report.jersey.JerseyConfig
import ru.vyarus.dropwizard.guice.debug.report.jersey.JerseyConfigRenderer
import ru.vyarus.dropwizard.guice.module.installer.install.binding.LazyBinding
import ru.vyarus.dropwizard.guice.test.jupiter.TestDropwizardApp
import spock.lang.Specification

import javax.inject.Inject
import javax.ws.rs.core.Response
import javax.ws.rs.ext.ExceptionMapper
import javax.ws.rs.ext.Provider

/**
 * @author Vyacheslav Rusakov
 * @since 28.10.2019
 */
@TestDropwizardApp(App)
class LazyRenderTest extends Specification {
    static {
        System.clearProperty(PropertyBundleLookup.BUNDLES_PROPERTY)
    }

    @Inject
    InjectionManager manager
    JerseyConfigRenderer renderer

    void setup() {
        renderer = new JerseyConfigRenderer(manager, true)
    }

    def "Check extended exception mapper render"() {

        expect:
        render(new JerseyConfig().showExceptionMappers()) == """

    Exception mappers
        Throwable                      ExceptionMapperBinder\$1      (io.dropwizard.core.setup)
        EofException                   EarlyEofExceptionMapper      (i.d.jersey.errors)
        EmptyOptionalException         EmptyOptionalExceptionMapper (i.d.jersey.optional)
        IllegalStateException          IllegalStateExceptionMapper  (i.d.jersey.errors)
        JerseyViolationException       JerseyViolationExceptionMapper (i.d.j.validation)
        JsonProcessingException        JsonProcessingExceptionMapper (i.d.jersey.jackson)
        IOException                    ExMapper                     (r.v.d.g.d.r.j.LazyRenderTest) *lazy
        ValidationException            ValidationExceptionMapper    (o.g.j.s.v.internal)
""" as String;
    }

    static class App extends Application<Configuration> {

        @Override
        void initialize(Bootstrap<Configuration> bootstrap) {
            bootstrap.addBundle(GuiceBundle.builder()
                    .extensions(ExMapper)
                    .printJerseyConfig()
                    .build())
        }

        @Override
        void run(Configuration configuration, Environment environment) throws Exception {
        }
    }

    @Provider
    @LazyBinding
    static class ExMapper implements ExceptionMapper<IOException> {

        @Override
        Response toResponse(IOException exception) {
            return null
        }
    }


    String render(JerseyConfig config) {
        renderer.renderReport(config).replaceAll("\r", "").replaceAll(" +\n", "\n")
    }
}
