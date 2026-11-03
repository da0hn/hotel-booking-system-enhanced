package com.hotel.booking.system.customer.service.core.domain.valueobject;

import com.hotel.booking.system.commons.core.domain.AbstractDomainList;
import com.hotel.booking.system.customer.service.core.domain.entity.ReservationOrderTimeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class Timeline extends AbstractDomainList<ReservationOrderTimeline> {
  protected Timeline(final List<ReservationOrderTimeline> data) {
    super(data);
  }

  public static Timeline empty() {
    return new Timeline(new ArrayList<>());
  }

  public static Timeline of(final List<ReservationOrderTimeline> items) {
    return new Timeline(items);
  }

  /**
   * A entrada mais recente pelo instante em que ocorreu, e não pela posição na lista: a lista vem
   * do banco sem ordem garantida. No empate de instantes vale a que está depois na lista.
   */
  public Optional<ReservationOrderTimeline> latest() {
    return this.data().stream()
      .reduce((left, right) -> right.getOccurredAt().isBefore(left.getOccurredAt()) ? left : right);
  }

  public <R> List<R> mapToListOf(final Function<? super ReservationOrderTimeline, R> mapper) {
    return this.data().stream()
      .sorted(Comparator.comparing(ReservationOrderTimeline::getOccurredAt).reversed())
      .map(mapper)
      .toList();
  }
}
