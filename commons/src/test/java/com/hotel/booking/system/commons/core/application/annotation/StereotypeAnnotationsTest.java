package com.hotel.booking.system.commons.core.application.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StereotypeAnnotationsTest {

  private static final List<Class<? extends Annotation>> STEREOTYPES = List.of(
    Listener.class,
    Publisher.class,
    Mapper.class,
    DomainService.class
  );

  @Test
  void shouldUseComponentAsStereotype() {
    STEREOTYPES.forEach(stereotype ->
      assertThat(stereotype.getAnnotation(Component.class))
        .as("Expected %s to use Component as a meta-annotation", stereotype.getSimpleName())
        .isNotNull()
    );
  }

  @Test
  void shouldBeRetainedAtRuntimeAndTargetTypes() {
    STEREOTYPES.forEach(stereotype -> {
      final var retention = stereotype.getAnnotation(Retention.class);
      final var target = stereotype.getAnnotation(Target.class);

      assertThat(retention).isNotNull();
      assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
      assertThat(target).isNotNull();
      assertThat(target.value()).containsExactly(ElementType.TYPE);
    });
  }
}
