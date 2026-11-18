package com.harsh.hospitalmanagement.config.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;

/**
 * Executes safety validation during ApplicationContext bootstrap and ensures complete coverage
 * across all execution modes and test slices (e.g., @DataJpaTest, @SpringBootTest, @WebMvcTest).
 * <p>
 * Key responsibilities:
 * 1. Validates the environment configuration immediately during ApplicationContext initialization.
 * 2. Registers a BeanFactoryPostProcessor that re-validates the environment after all dynamic property
 *    sources (such as @DynamicPropertySource) are applied, but BEFORE any singleton beans (DataSource,
 *    EntityManagerFactory) are instantiated.
 * 3. Registers a RootBeanDefinition for DatabaseSafetyBeanPostProcessor into the BeanDefinitionRegistry.
 *    This ensures Spring's PostProcessorRegistrationDelegate picks it up and sorts it according to
 *    PriorityOrdered (HIGHEST_PRECEDENCE + 10), guaranteeing it runs AFTER ConfigurationPropertiesBindingPostProcessor
 *    (HIGHEST_PRECEDENCE + 1) across all contexts and test slices (@DataJpaTest, @JdbcTest, @WebMvcTest).
 * 4. Adds a ContextRefreshedEvent listener as a final defense-in-depth verification before context startup completes.
 */
public class DatabaseSafetyApplicationContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSafetyApplicationContextInitializer.class);

    public static final String BEAN_NAME = "databaseSafetyBeanPostProcessor";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        log.debug("HMS Database Safety Initializer: initializing context safety gates...");

        // 1. Immediate validation of existing environment
        DatabaseSafetyValidator.validateEnvironment(applicationContext.getEnvironment());

        // 2. Register BeanFactoryPostProcessor: runs AFTER all dynamic property sources (@DynamicPropertySource)
        // are applied to the Environment, but BEFORE any singleton beans (DataSource, EntityManagerFactory) are instantiated.
        applicationContext.addBeanFactoryPostProcessor(beanFactory -> {
            log.debug("HMS Database Safety Initializer: validating environment in BeanFactoryPostProcessor before singleton creation...");
            DatabaseSafetyValidator.validateEnvironment(applicationContext.getEnvironment());
        });

        // 3. Register DatabaseSafetyBeanPostProcessor as an infrastructure BeanDefinition in the BeanDefinitionRegistry.
        // This ensures Spring standard bean post-processor lifecycle and PriorityOrdered sorting relative
        // to Spring Boot's ConfigurationPropertiesBindingPostProcessor are fully honored across all contexts and slices.
        registerSafetyBeanPostProcessorDefinition(applicationContext);

        // 4. Re-validate upon context refresh to catch any late property modifications before traffic begins
        applicationContext.addApplicationListener((ContextRefreshedEvent event) -> {
            if (event.getApplicationContext() == applicationContext) {
                DatabaseSafetyValidator.validateEnvironment(applicationContext.getEnvironment());
            }
        });
    }

    private void registerSafetyBeanPostProcessorDefinition(ConfigurableApplicationContext applicationContext) {
        BeanDefinitionRegistry registry = null;
        if (applicationContext instanceof BeanDefinitionRegistry bdr) {
            registry = bdr;
        } else if (applicationContext.getBeanFactory() instanceof BeanDefinitionRegistry bdr) {
            registry = bdr;
        }

        if (registry != null) {
            if (!registry.containsBeanDefinition(BEAN_NAME)
                    && !registry.containsBeanDefinition(DatabaseSafetyBeanPostProcessor.class.getName())) {
                RootBeanDefinition def = new RootBeanDefinition(DatabaseSafetyBeanPostProcessor.class);
                def.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
                registry.registerBeanDefinition(BEAN_NAME, def);
                log.debug("HMS Database Safety Initializer: registered infrastructure BeanDefinition '{}'", BEAN_NAME);
            }
        } else {
            // Fallback only if context is not a BeanDefinitionRegistry: add directly
            applicationContext.getBeanFactory().addBeanPostProcessor(new DatabaseSafetyBeanPostProcessor());
        }
    }
}
