package ingressos.akka.comandos;
import akka.actor.typed.ActorRef;
import ingressos.akka.Comando;
import ingressos.akka.Respostas;

public record Comprar(long eventoId, String cliente, int quantidade,
                      ActorRef<Respostas.VendaResp> replyTo) implements Comando {}