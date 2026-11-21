package ingressos.simulacao;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.*;
import ingressos.servico.Repositorio;

import java.util.concurrent.CompletableFuture;

public class SimuladorActor extends AbstractBehavior<SimuladorActor.Comando> {

    public interface Comando {}
    public record ExecutarSimulacao(int usuarios, int opsPorUsuario, int capacidade,
                                    long seed, ActorRef<Simulador.Resultado> replyTo) implements Comando {}

    private final Repositorio repositorio;

    public static Behavior<Comando> create(Repositorio repositorio) {
        return Behaviors.setup(ctx -> new SimuladorActor(ctx, repositorio));
    }

    private SimuladorActor(ActorContext<Comando> ctx, Repositorio repositorio) {
        super(ctx);
        this.repositorio = repositorio;
    }

    @Override
    public Receive<Comando> createReceive() {
        return newReceiveBuilder()
            .onMessage(ExecutarSimulacao.class, this::onExecutar)
            .build();
    }

    private Behavior<Comando> onExecutar(ExecutarSimulacao cmd) {
        ActorRef<Simulador.Resultado> replyTo = cmd.replyTo();
        // Executa em thread separada para não bloquear o mailbox do ator
        CompletableFuture.supplyAsync(() -> {
            try {
                return new Simulador().executar(repositorio, cmd.usuarios(),
                        cmd.opsPorUsuario(), cmd.capacidade(), cmd.seed());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((resultado, erro) -> {
            if (erro != null) {
                getContext().getLog().error("simulação falhou", erro);
                replyTo.tell(null); // ou um Resultado de erro
            } else {
                replyTo.tell(resultado);
            }
        });
        return this;
    }
}