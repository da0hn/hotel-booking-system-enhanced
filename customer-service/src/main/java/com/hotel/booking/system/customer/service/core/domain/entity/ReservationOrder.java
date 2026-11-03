package com.hotel.booking.system.customer.service.core.domain.entity;

import com.hotel.booking.system.commons.core.domain.AbstractDomainEntity;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerId;
import com.hotel.booking.system.commons.core.domain.valueobject.CustomerReservationStatus;
import com.hotel.booking.system.commons.core.domain.valueobject.FailureMessages;
import com.hotel.booking.system.commons.core.domain.valueobject.HotelId;
import com.hotel.booking.system.commons.core.domain.valueobject.Money;
import com.hotel.booking.system.commons.core.domain.valueobject.ReservationOrderId;
import com.hotel.booking.system.commons.core.message.ApplicationMessage;
import com.hotel.booking.system.customer.service.core.domain.exception.CustomerDomainException;
import com.hotel.booking.system.customer.service.core.domain.valueobject.Timeline;
import lombok.Builder;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.Objects;

@SuperBuilder
public class ReservationOrder extends AbstractDomainEntity<ReservationOrderId> {

  @Builder.Default
  private Timeline timeline = Timeline.empty();
  private CustomerId customerId;
  private HotelId hotelId;
  private Integer guests;
  private LocalDate checkIn;
  private LocalDate checkOut;
  private Money totalPrice;
  private CustomerReservationStatus currentStatus;


  protected ReservationOrder(final ReservationOrderId id) {
    super(id);
  }

  public void initialize() {
    if (Objects.isNull(this.getId())) {
      this.setId(ReservationOrderId.newInstance());
    }
    this.updateStatus(CustomerReservationStatus.AWAITING_RESERVATION);
  }


  /**
   * Registra a transição para o status, sem motivo. Idempotente: se a entrada mais recente da
   * timeline já descreve essa mesma transição, nada muda (ver {@link #record}).
   *
   * @return {@code true} se a transição entrou na timeline; {@code false} se era repetida
   */
  public boolean updateStatus(final CustomerReservationStatus status) {
    return this.record(ReservationOrderTimeline.update(status));
  }

  /**
   * Registra a transição para um status de falha, com os motivos. Tem a mesma idempotência de
   * {@link #updateStatus}, e o motivo faz parte da comparação: o mesmo status que volta com outro
   * motivo é informação nova e entra.
   *
   * @return {@code true} se a transição entrou na timeline; {@code false} se era repetida
   */
  public boolean updateToFailureStatus(final CustomerReservationStatus status, FailureMessages failureMessages) {
    if (!CustomerReservationStatus.isFailureStatus(status)) {
      throw new CustomerDomainException(ApplicationMessage.CUSTOMER_RESERVATION_ORDER_STATUS_INVALID_STATE);
    }
    return this.record(ReservationOrderTimeline.update(status, failureMessages));
  }

  /**
   * Uma transição é repetição quando tem o mesmo status e o mesmo motivo que a entrada mais
   * recente da timeline ({@link ReservationOrderTimeline#isSameTransitionAs}). É o que torna a
   * projeção naturalmente idempotente (regra 3 da issue #7): o RabbitMQ entrega ao menos uma vez,
   * e a mesma transição pode chegar de novo com um {@code eventId} que a deduplicação por evento
   * não reconhece, seja fora da janela de expurgo, seja numa republicação.
   * <p>
   * A comparação é só com a última entrada, de propósito: o status que se repete em sequência é
   * reentrega, mas o que reaparece depois de outro pode ser uma volta legítima (uma nova tentativa
   * de pagamento, se a saga passar a ter uma), e uma tabela de transições permitidas é uma decisão
   * de domínio maior que esta. Fica de fora, portanto, a reentrega tardia de um status <em>anterior</em> à
   * última entrada. E a corrida de duas transações que leem o pedido antes de qualquer uma gravar
   * não se resolve aqui: é a issue #53.
   */
  private boolean record(final ReservationOrderTimeline transition) {
    final var repeated = this.timeline.latest().filter(transition::isSameTransitionAs).isPresent();
    if (repeated) {
      return false;
    }
    this.currentStatus = transition.getStatus();
    this.timeline.add(transition);
    return true;
  }

  public Timeline getTimeline() {
    return this.timeline;
  }

  public CustomerId getCustomerId() {
    return this.customerId;
  }

  public HotelId getHotelId() {
    return this.hotelId;
  }

  public Integer getGuests() {
    return this.guests;
  }

  public LocalDate getCheckIn() {
    return this.checkIn;
  }

  public LocalDate getCheckOut() {
    return this.checkOut;
  }

  public Money getTotalPrice() {
    return this.totalPrice;
  }

  public CustomerReservationStatus getCurrentStatus() {
    return this.currentStatus;
  }

}
