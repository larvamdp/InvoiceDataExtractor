package ar.comprobantes.extractor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Página de pruebas interna: /pruebas y /pruebas/ abren el index estático (Spring solo resuelve el de la raíz). */
@Configuration
public class PaginaPruebasConfig implements WebMvcConfigurer {

    private static final String INDEX = "forward:/pruebas/index.html";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/pruebas").setViewName(INDEX);
        registry.addViewController("/pruebas/").setViewName(INDEX);
    }
}
