package ingressos.akka.comandos;
import akka.actor.typed.ActorRef;
import ingressos.akka.Comando;
import ingressos.akka.Respostas;

public record BuscarPorNome(String termo, ActorRef<Respostas.EventosResp> replyTo) implements Comando {}