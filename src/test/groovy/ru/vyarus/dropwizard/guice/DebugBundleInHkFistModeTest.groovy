package ru.vyarus.dropwizard.guice

import com.google.inject.Injector
import com.google.inject.ProvisionException
import io.dropwizard.core.Application
import io.dropwizard.core.setup.Bootstrap
import io.dropwizard.core.setup.Environment
import org.glassfish.hk2.api.MultiException
import org.glassfish.jersey.internal.inject.AbstractBinder
import org.glassfish.jersey.internal.inject.InjectionManager
import ru.vyarus.dropwizard.guice.module.installer.feature.jersey.GuiceManaged
import ru.vyarus.dropwizard.guice.module.installer.feature.jersey.ResourceInstaller
import ru.vyarus.dropwizard.guice.module.installer.feature.jersey.provider.JerseyProviderInstaller
import ru.vyarus.dropwizard.guice.module.jersey.debug.HK2DebugBundle
import ru.vyarus.dropwizard.guice.module.jersey.debug.service.ContextDebugService
import ru.vyarus.dropwizard.guice.module.jersey.debug.service.WrongContextException
import ru.vyarus.dropwizard.guice.support.TestConfiguration
import ru.vyarus.dropwizard.guice.support.util.GuiceRestrictedConfigBundle
import ru.vyarus.dropwizard.guice.test.jupiter.TestDropwizardApp

import javax.inject.Inject
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.core.Response
import javax.ws.rs.ext.ExceptionMapper
import javax.ws.rs.ext.Provider

/**
 * @author Vyacheslav Rusakov
 * @since 28.04.2018
 */
@TestDropwizardApp(DebugApp)
class DebugBundleInHkFistModeTest extends AbstractTest {

    @Inject
    ContextDebugService debugService
    @Inject
    Injector injector
    @Inject
    javax.inject.Provider<InjectionManager> locator;

    def "Check correct scopes"() {

        setup: "need to request hk resource to force instantiation"
        // no guice rest check because it will lead to resource instantiation by hk, but it shouldn't do it (see qualified declaration)
//        new URL("http://localhost:8080/guice/foo").getText()
        new URL("http://localhost:8080/hk/foo").getText()
        // initialize exception mappers
        locator.get().getAllInstances(ExceptionMapper)

        expect:
        debugService.guiceManaged as Set == [GuiceResource, GuiceMapper] as Set
        debugService.hkManaged as Set == [HkResource, HkMapper] as Set

    }

    def "Check detection"() {

        when: "forcing guice to create hk bean"
        injector.getInstance(HkResource)
        then: "detected"
        def ex = thrown(ProvisionException)
        ex.getCause() instanceof WrongContextException

        when: "force hk to create guice bean"
        locator.get().getInstance(GuiceResource, "test")
        then:
        ex = thrown(MultiException)
        ex.getErrors()[0] instanceof WrongContextException

    }

    static class DebugApp extends Application<TestConfiguration> {

        @Override
        void initialize(Bootstrap<TestConfiguration> bootstrap) {
            bootstrap.addBundle(GuiceBundle.builder()
                    .useHK2ForJerseyExtensions()
                    .bundles(new HK2DebugBundle())
                    .disableBundles(GuiceRestrictedConfigBundle)
                    .noDefaultInstallers()
                    .printDiagnosticInfo()
                    .installers(ResourceInstaller, JerseyProviderInstaller)
                    .extensions(GuiceResource, HkResource, GuiceMapper, HkMapper)
                    .build())
        }

        @Override
        void run(TestConfiguration configuration, Environment environment) throws Exception {
            environment.jersey().register(new AbstractBinder() {
                @Override
                protected void configure() {
                    // debug bundle ignores qualifiers.. using it to instantiate wrong class
                    bind(GuiceResource).named("test").to(GuiceResource)
                }
            })
        }
    }

    @Path("/guice")
    @GuiceManaged
    static class GuiceResource {
        @Path("/foo")
        @GET
        String foo() {
            return ""
        }
    }

    @Path("/hk")
    static class HkResource {
        @Path("/foo")
        @GET
        String foo() {
            return ""
        }
    }

    @Provider
    @GuiceManaged
    static class GuiceMapper implements ExceptionMapper<IOException> {
        @Override
        Response toResponse(IOException exception) {
            return null
        }
    }

    @Provider
    static class HkMapper implements ExceptionMapper<IOException> {
        @Override
        Response toResponse(IOException exception) {
            return null
        }
    }
}
