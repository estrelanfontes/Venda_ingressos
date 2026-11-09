package ingressos.modelo;

import java.math.BigDecimal;
import java.time.Instant;

/** Compra de ingressos. Imutável, portanto pode ser compartilhada entre threads sem proteção. */
public record Venda(long id, long eventoId, String cliente, int quantidade, BigDecimal total, Instant data) {
}
