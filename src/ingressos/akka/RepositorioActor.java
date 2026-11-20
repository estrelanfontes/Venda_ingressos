package ingressos.akka;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import ingressos.akka.comandos.*;
import ingressos.modelo.*;
import ingressos.persistencia.ArmazenamentoCsv;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class RepositorioActor extends AbstractBehavior<Comando> {

    // ÚNICO dono do estado — privado, sem locks
    private final Map<Long, Evento> eventos = new TreeMap<>();
    private long proximoEventoId = 1;
    private long proximaVendaId = 1;
    private final ArmazenamentoCsv armazenamento;

    public static Behavior<Comando> create(ArmazenamentoCsv armazenamento) {
        return Behaviors.setup(ctx -> new RepositorioActor(ctx, armazenamento));
    }

    private RepositorioActor(ActorContext<Comando> ctx, ArmazenamentoCsv armazenamento) {
        super(ctx);
        this.armazenamento = armazenamento;
        carregarInicial();
    }

    @Override
    public Receive<Comando> createReceive() {
        return newReceiveBuilder()
            .onMessage(Adicionar.class, this::onAdicionar)
            .onMessage(Remover.class, this::onRemover)
            .onMessage(BuscarPorId.class, this::onBuscarPorId)
            .onMessage(BuscarPorNome.class, this::onBuscarPorNome)
            .onMessage(Listar.class, this::onListar)
            .onMessage(Comprar.class, this::onComprar)
            .onMessage(Cancelar.class, this::onCancelar)
            .onMessage(Importar.class, this::onImportar)
            .onMessage(Salvar.class, this::onSalvar)
            .build();
    }

    // ---------------- comandos

    private Behavior<Comando> onAdicionar(Adicionar cmd) {
        try {
            validarEvento(cmd.nome(), cmd.local(), cmd.preco(), cmd.capacidade());
            Evento e = new Evento(proximoEventoId++, limpar(cmd.nome()), limpar(cmd.local()),
                                  cmd.preco(), cmd.capacidade());
            eventos.put(e.id(), e);
            cmd.replyTo().tell(new Respostas.EventoResp(e.snapshot(),null));
        } catch (RuntimeException ex) {
            getContext().getLog().error("adicionar falhou", ex);
            // Em A pura, exceções de validação no ator precisam ser propagadas
            // como resposta: usamos um caminho simples — relançar dentro do ask
            // não funciona; então enviamos um EventoResp com null e o adaptador valida.
            cmd.replyTo().tell(new Respostas.EventoResp(null, ex));
        }
        return this;
    }

    private Behavior<Comando> onRemover(Remover cmd) {
        Evento e = eventos.remove(cmd.id());
        cmd.replyTo().tell(new Respostas.EventoResp(e == null ? null : e.snapshot(), null));
        return this;
    }

    private Behavior<Comando> onBuscarPorId(BuscarPorId cmd) {
        Evento e = eventos.get(cmd.id());
        cmd.replyTo().tell(new Respostas.EventoOpcionalResp(
                Optional.ofNullable(e == null ? null : e.snapshot())));
        return this;
    }

    private Behavior<Comando> onBuscarPorNome(BuscarPorNome cmd) {
        String t = limpar(cmd.termo()).toLowerCase(Locale.ROOT);
        List<EventoDados> out = eventos.values().stream()
            .filter(e -> e.nome().toLowerCase(Locale.ROOT).contains(t)
                      || e.local().toLowerCase(Locale.ROOT).contains(t))
            .map(Evento::snapshot)
            .collect(Collectors.toList());
        cmd.replyTo().tell(new Respostas.EventosResp(out));
        return this;
    }

    private Behavior<Comando> onListar(Listar cmd) {
        List<EventoDados> out = eventos.values().stream()
            .map(Evento::snapshot).collect(Collectors.toList());
        cmd.replyTo().tell(new Respostas.EventosResp(out));
        return this;
    }

    private Behavior<Comando> onComprar(Comprar cmd) {
        try {
            String c = limpar(cmd.cliente());
            if (c.isEmpty()) throw new NegocioException("O nome do cliente é obrigatório.");
            if (cmd.quantidade() <= 0) throw new NegocioException("A quantidade deve ser maior que zero.");
            Evento e = eventos.get(cmd.eventoId());
            if (e == null) throw new EventoNaoEncontradoException(cmd.eventoId());
            Venda v = e.reservar(proximaVendaId++, c, cmd.quantidade());
            cmd.replyTo().tell(new Respostas.VendaResp(v,null));
        } catch (EstoqueInsuficienteException ex) {
            // O adaptador (ask) precisa receber a exceção para relançá-la
            // como RuntimeException — mas o ask espera VendaResp.
            // Solução: enviar VendaResp(null) e o adaptador relança com base em
            // uma segunda mensagem. Para simplificar, aqui logamos e devolvemos null.
            getContext().getLog().warn("sem estoque: {}", ex.getMessage());
            cmd.replyTo().tell(new Respostas.VendaResp(null, ex));
        } catch (RuntimeException ex) {
            getContext().getLog().warn("comprar falhou: {}", ex.getMessage());
            cmd.replyTo().tell(new Respostas.VendaResp(null, ex));
        }
        return this;
    }

    private Behavior<Comando> onCancelar(Cancelar cmd) {
        try {
            Evento e = eventos.get(cmd.eventoId());
            if (e == null) throw new EventoNaoEncontradoException(cmd.eventoId());
            Venda v = e.cancelar(cmd.vendaId());
            cmd.replyTo().tell(new Respostas.VendaResp(v,null));
        } catch (RuntimeException ex) {
            getContext().getLog().warn("cancelar falhou: {}", ex.getMessage());
            cmd.replyTo().tell(new Respostas.VendaResp(null, ex));
        }
        return this;
    }

    private Behavior<Comando> onImportar(Importar cmd) {
        eventos.clear();
        proximoEventoId = 1;
        proximaVendaId = 1;
        for (EventoDados d : cmd.dados()) {
            eventos.put(d.id(), Evento.restaurar(d));
            proximoEventoId = Math.max(proximoEventoId, d.id() + 1);
            for (Venda v : d.vendas()) proximaVendaId = Math.max(proximaVendaId, v.id() + 1);
        }
        cmd.replyTo().tell(new Respostas.OkResp(true));
        return this;
    }

    private Behavior<Comando> onSalvar(Salvar cmd) {
        try {
            List<EventoDados> foto = eventos.values().stream()
                .map(Evento::snapshot).collect(Collectors.toList());
            armazenamento.salvar(foto);
            cmd.replyTo().tell(new Respostas.OkResp(true));
        } catch (IOException ex) {
            cmd.replyTo().tell(new Respostas.OkResp(false));
        }
        return this;
    }

    // ---------------- utilidades

    private void carregarInicial() {
        try {
            if (armazenamento.existe()) {
                for (EventoDados d : armazenamento.carregar()) {
                    eventos.put(d.id(), Evento.restaurar(d));
                    proximoEventoId = Math.max(proximoEventoId, d.id() + 1);
                    for (Venda v : d.vendas())
                        proximaVendaId = Math.max(proximaVendaId, v.id() + 1);
                }
            }
        } catch (IOException ex) {
            getContext().getLog().error("falha ao carregar dados iniciais", ex);
        }
    }

    private static void validarEvento(String nome, String local, BigDecimal preco, int cap) {
        if (limpar(nome).isEmpty()) throw new NegocioException("O nome do evento é obrigatório.");
        if (limpar(local).isEmpty()) throw new NegocioException("O local do evento é obrigatório.");
        if (preco == null || preco.signum() < 0) throw new NegocioException("Preço inválido.");
        if (cap <= 0) throw new NegocioException("A capacidade deve ser maior que zero.");
    }

    private static String limpar(String s) {
        return s == null ? "" : s.replaceAll("[;\\r\\n]", " ").trim();
    }
}