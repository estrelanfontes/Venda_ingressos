package ingressos.akka.comandos;
import akka.actor.typed.ActorRef;
import ingressos.akka.Comando;
import ingressos.akka.Respostas;

public record Remover(long id, ActorRef<Respostas.EventoResp> replyTo) implements Comando {}