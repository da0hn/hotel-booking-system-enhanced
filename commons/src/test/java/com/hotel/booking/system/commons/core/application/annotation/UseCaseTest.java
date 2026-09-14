package com.hotel.booking.system.commons.core.application.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class UseCaseTest {

  @Test
  void shouldUseComponentAsStereotype() {
    assertThat(UseCase.class.getAnnotation(Component.class)).isNotNull();
  }

  @Test
  void shouldBeRetainedAtRuntimeAndTargetTypes() {
    final var retention = UseCase.class.getAnnotation(Retention.class);
    final var target = UseCase.class.getAnnotation(Target.class);

    assertThat(retention).isNotNull();
    assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    assertThat(target).isNotNull();
    assertThat(target.value()).containsExactly(ElementType.TYPE);
  }
}
