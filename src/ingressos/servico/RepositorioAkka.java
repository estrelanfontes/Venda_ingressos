package ingressos.servico;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import ingressos.akka.Comando;
import ingressos.akka.RepositorioActor;
import ingressos.akka.Respostas;
import ingressos.akka.comandos.*;
import ingressos.modelo.*;
import ingressos.persistencia.ArmazenamentoCsv;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public class RepositorioAkka implements Repositorio {

    private final ActorSystem<Comando> system;
    private final ActorRef<Comando> ator;
    private final Duration timeout = Duration.ofSeconds(10);

    public RepositorioAkka(ArmazenamentoCsv armazenamento) {
        this.system = ActorSystem.create(RepositorioActor.create(armazenamento), "repositorio-akka");
        this.ator = system;
    }

    @Override
    public EventoDados adicionarEvento(String nome, String local, BigDecimal preco, int capacidade) {
        Respostas.EventoResp r = ask(replyTo ->
            new Adicionar(nome, local, preco, capacidade, replyTo));
        if (r.erro() != null) throw r.erro();
        return r.evento();
    }

    @Override
    public EventoDados removerEvento(long id) {
        Respostas.EventoResp r = ask(replyTo -> new Remover(id, replyTo));
        if (r.erro() != null) throw r.erro();
        if (r.evento() == null) throw new EventoNaoEncontradoException(id);
        return r.evento();
    }

    @Override
    public EventoDados buscarPorId(long id) {
        Respostas.EventoOpcionalResp r = ask(replyTo -> new BuscarPorId(id, replyTo));
        return r.evento().orElseThrow(() -> new EventoNaoEncontradoException(id));
    }

    @Override
    public List<EventoDados> buscarPorNome(String termo) {
        CompletionStage<Respostas.EventosResp> fut = AskPattern.ask(
            ator,
            (ActorRef<Respostas.EventosResp> replyTo) -> new BuscarPorNome(termo, replyTo),
            timeout,
            system.scheduler());
        return fut.toCompletableFuture().join().eventos();
    }

    @Override
    public List<EventoDados> listar() {
        CompletionStage<Respostas.EventosResp> fut = AskPattern.ask(
            ator,
            (ActorRef<Respostas.EventosResp> replyTo) -> new Listar(replyTo),
            timeout,
            system.scheduler());
        return fut.toCompletableFuture().join().eventos();
    }

    @Override
    public Venda comprar(long eventoId, String cliente, int quantidade) {
        Respostas.VendaResp r = ask(replyTo ->
            new Comprar(eventoId, cliente, quantidade, replyTo));
        if (r.erro() != null) throw r.erro();
        if (r.venda() == null) throw new NegocioException("Compra não concluída.");
        return r.venda();
    }

    @Override
    public Venda cancelarCompra(long eventoId, long vendaId) {
        Respostas.VendaResp r = ask(replyTo -> new Cancelar(eventoId, vendaId, replyTo));
        if (r.erro() != null) throw r.erro();
        if (r.venda() == null) throw new NegocioException("Cancelamento não concluído.");
        return r.venda();
    }

    @Override
    public List<EventoDados> exportar() {
        return listar();
    }

    @Override
    public void importar(List<EventoDados> dados) {
        Respostas.OkResp r = ask(replyTo -> new Importar(dados, replyTo));
        if (!r.ok()) throw new NegocioException("Falha ao importar.");
    }

    @Override
    public void salvar() {
        Respostas.OkResp r = ask(replyTo -> new Salvar(replyTo));
        if (!r.ok()) throw new NegocioException("Falha ao salvar.");
    }

    public void encerrar() {
        system.terminate();
    }

    // helper para reduzir boilerplate
    private <T> T ask(java.util.function.Function<ActorRef<T>, Comando> fabrica) {
        CompletionStage<T> fut = AskPattern.ask(ator, fabrica::apply, timeout, system.scheduler());
        return fut.toCompletableFuture().join();
    }
}