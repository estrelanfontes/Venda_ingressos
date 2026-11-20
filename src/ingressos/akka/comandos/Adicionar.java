package ingressos.akka.comandos;
import akka.actor.typed.ActorRef;
import ingressos.akka.Comando;
import ingressos.akka.Respostas;
import java.math.BigDecimal;

public record Adicionar(String nome, String local, BigDecimal preco, int capacidade,
                        ActorRef<Respostas.EventoResp> replyTo) implements Comando {}