package ingressos.akka;

import ingressos.modelo.EventoDados;
import ingressos.modelo.Venda;
import java.util.List;
import java.util.Optional;

public final class Respostas {
    private Respostas() {}

    public record EventoResp(EventoDados evento, RuntimeException erro) {}
    public record EventoOpcionalResp(Optional<EventoDados> evento) {}
    public record EventosResp(List<EventoDados> eventos) {}
    public record VendaResp(Venda venda, RuntimeException erro) {}
    public record OkResp(boolean ok) {}
    public record ErroResp(RuntimeException erro) {}   // para propagar exceções de negócio
}