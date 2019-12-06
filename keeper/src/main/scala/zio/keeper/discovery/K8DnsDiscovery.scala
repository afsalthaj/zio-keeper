package zio.keeper.discovery

import java.net.UnknownHostException
import java.util

import javax.naming.directory.InitialDirContext
import javax.naming.{ Context, NamingException }
import zio.console.Console
import zio.duration.Duration
import zio.keeper.ServiceDiscoveryError
import zio.macros.delegate._
import zio.nio.{ InetAddress, SocketAddress }
import zio.{ IO, URIO, ZIO, keeper }

/**
 * This discovery strategy uses K8 service headless service dns to find other members of the cluster.
 *
 * Headless service is a service of type ClusterIP with the clusterIP property set to None.
 *
 */
trait K8DnsDiscovery extends Discovery.Service[Any] {

  val console: Console.Service[Any]

  import console._

  final override val discoverNodes: ZIO[Any, keeper.Error, Set[SocketAddress]] = {
    for {
      addresses <- lookup(serviceDns, serviceDnsTimeout)
      nodes     <- ZIO.foreach(addresses)(addr => zio.nio.SocketAddress.inetSocketAddress(addr, servicePort))
    } yield nodes.toSet: Set[zio.nio.SocketAddress]
  }.catchAll(
    ex =>
      putStrLn("discovery strategy " + this.getClass.getSimpleName + " failed.") *>
        ZIO.fail(ServiceDiscoveryError(ex.getMessage))
  )

  def lookup(serviceDns: InetAddress, serviceDnsTimeout: Duration) = {
    import scala.jdk.CollectionConverters._
    val env = new util.Hashtable[String, String]
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
    env.put(Context.PROVIDER_URL, "dns:")
    env.put("com.sun.jndi.dns.timeout.initial", serviceDnsTimeout.toMillis.toString)

    for {
      dirContext   <- ZIO.effect(new InitialDirContext(env)).refineToOrDie[NamingException]
      attributes   <- ZIO.effectTotal(dirContext.getAttributes(serviceDns.hostname, Array[String]("SRV")))
      srvAttribute = Option(attributes.get("srv")).toList.flatMap(_.getAll.asScala)
      addresses <- ZIO.foldLeft(srvAttribute)(Set.empty[zio.nio.InetAddress]) {
                    case (acc, address: String) =>
                      extractHost(address)
                        .flatMap(zio.nio.InetAddress.byName)
                        .map(acc + _)
                        .refineToOrDie[UnknownHostException]
                    case (acc, _) =>
                      ZIO.succeed(acc)
                  }
    } yield addresses
  }

  private def extractHost(server: String) =
    putStrLn("k8 dns on response: " + server) *>
      IO.effectTotal {
        val host = server.split(" ")(3)
        host.replaceAll("\\\\.$", "")
      }

  def serviceDns: zio.nio.InetAddress

  def serviceDnsTimeout: Duration

  def servicePort: Int

}

object K8DnsDiscovery {

  def k8DnsDiscovery(
    addr: zio.nio.InetAddress,
    timeout: Duration,
    port: Int
  ): URIO[Console, Discovery] =
    ZIO.access[Console](
      env =>
        new Discovery {

          override def discover: Discovery.Service[Any] = new K8DnsDiscovery {
            override val console: Console.Service[Any] = env.console

            override def serviceDns: InetAddress = addr

            override def serviceDnsTimeout: Duration = timeout

            override def servicePort: Int = port
          }
        }
    )

  def withK8DnsDiscovery(
    addr: zio.nio.InetAddress,
    timeout: Duration,
    port: Int
  ) = enrichWithM[Discovery](k8DnsDiscovery(addr, timeout, port))

}