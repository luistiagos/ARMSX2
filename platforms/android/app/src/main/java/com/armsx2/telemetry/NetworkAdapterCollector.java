/*

By MoonPower (Momo-AUX1) GPLv3 License
   This file is part of ARMSX2.

   ARMSX2 is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

*/

package com.armsx2.telemetry;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Collects a best-effort network snapshot for connectivity error telemetry. */
public final class NetworkAdapterCollector {
    private static final int MAX_ADAPTERS = 16;
    private static final int MAX_ADDRESSES = 32;
    private static final int MAX_DNS_SERVERS = 16;
    private static final int MAX_ROUTES = 32;

    private NetworkAdapterCollector() {}

    /**
     * Returns the interfaces visible to the app. Collection never throws and does not request
     * location, phone, Wi-Fi, or hardware-address permissions.
     */
    public static JSONArray collect(Context context) {
        JSONArray result = new JSONArray();
        if (context == null) return result;

        try {
            Map<String, AdapterSnapshot> adapters = enumerateInterfaces();
            addConnectivityDetails(context, adapters);

            int count = 0;
            for (AdapterSnapshot adapter : adapters.values()) {
                if (count++ >= MAX_ADAPTERS) break;
                try {
                    result.put(adapter.toJson());
                } catch (Throwable ignored) {
                    // One unusual vendor interface must not hide the remaining adapters.
                }
            }
        } catch (Throwable ignored) {
            // Telemetry is diagnostic only and must never affect application behavior.
        }
        return result;
    }

    private static Map<String, AdapterSnapshot> enumerateInterfaces() {
        Map<String, AdapterSnapshot> adapters = new TreeMap<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return adapters;
            for (NetworkInterface networkInterface : Collections.list(interfaces)) {
                try {
                    AdapterSnapshot adapter = new AdapterSnapshot(networkInterface);
                    if (!adapter.name.isEmpty()) adapters.put(adapter.name, adapter);
                } catch (Throwable ignored) {
                    // Keep collecting other interfaces.
                }
            }
        } catch (Throwable ignored) {
            // Returning an empty/partial snapshot is preferable to affecting error reporting.
        }
        return adapters;
    }

    @SuppressWarnings("deprecation")
    private static void addConnectivityDetails(
            Context context, Map<String, AdapterSnapshot> adapters) {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return;

            Network activeNetwork = manager.getActiveNetwork();
            // Needs proper testing on devices with concurrent VPN and cellular networks. The
            // deprecated synchronous snapshot is intentional: telemetry cannot leave a callback
            // registered after the report has already been sent.
            Network[] networks = manager.getAllNetworks();
            if (networks == null) return;

            for (Network network : networks) {
                try {
                    LinkProperties properties = manager.getLinkProperties(network);
                    if (properties == null || properties.getInterfaceName() == null) continue;

                    AdapterSnapshot adapter = adapters.get(properties.getInterfaceName());
                    if (adapter == null) {
                        NetworkInterface networkInterface =
                                NetworkInterface.getByName(properties.getInterfaceName());
                        if (networkInterface == null) continue;
                        adapter = new AdapterSnapshot(networkInterface);
                        adapters.put(adapter.name, adapter);
                    }

                    adapter.active |= network.equals(activeNetwork);
                    adapter.add(properties);

                    NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                    if (capabilities != null) adapter.add(capabilities);
                } catch (Throwable ignored) {
                    // Networks can disappear between getAllNetworks() and the detail queries.
                }
            }
        } catch (Throwable ignored) {
            // ACCESS_NETWORK_STATE may be unavailable on a vendor build; base interface data stays.
        }
    }

    private static JSONArray strings(Iterable<String> values, int limit) {
        JSONArray result = new JSONArray();
        int count = 0;
        for (String value : values) {
            if (count++ >= limit) break;
            result.put(value);
        }
        return result;
    }

    private static final class AdapterSnapshot {
        final String name;
        final String displayName;
        final boolean up;
        final boolean loopback;
        final boolean virtual;
        final boolean multicast;
        final Set<String> addresses = new TreeSet<>();
        final Set<String> dnsServers = new TreeSet<>();
        final Set<String> transports = new TreeSet<>();
        final Map<String, RouteSnapshot> routes = new TreeMap<>();
        int mtu;
        boolean active;
        boolean hasIpv6;
        boolean hasGlobalIpv6;
        boolean internet;
        boolean validated;
        boolean captivePortal;
        boolean metered;

        AdapterSnapshot(NetworkInterface networkInterface) throws Exception {
            name = valueOrEmpty(networkInterface.getName());
            displayName = valueOrEmpty(networkInterface.getDisplayName());
            up = networkInterface.isUp();
            loopback = networkInterface.isLoopback();
            virtual = networkInterface.isVirtual();
            multicast = networkInterface.supportsMulticast();
            mtu = networkInterface.getMTU();

            Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
            if (inetAddresses == null) return;
            for (InetAddress address : Collections.list(inetAddresses)) addAddress(address);
        }

        void add(LinkProperties properties) {
            if (properties.getMtu() > 0) mtu = properties.getMtu();
            for (InetAddress dns : properties.getDnsServers()) {
                if (dns != null) dnsServers.add(dns.getHostAddress());
            }
            for (RouteInfo route : properties.getRoutes()) {
                RouteSnapshot snapshot = new RouteSnapshot(route);
                routes.put(snapshot.key(), snapshot);
            }
        }

        void add(NetworkCapabilities capabilities) {
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_CELLULAR, "cellular");
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_WIFI, "wifi");
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_BLUETOOTH, "bluetooth");
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_ETHERNET, "ethernet");
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_VPN, "vpn");
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_WIFI_AWARE, "wifi_aware");
            addTransport(capabilities, NetworkCapabilities.TRANSPORT_LOWPAN, "lowpan");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addTransport(capabilities, NetworkCapabilities.TRANSPORT_USB, "usb");
            }
            if (Build.VERSION.SDK_INT >= 35) {
                addTransport(capabilities, NetworkCapabilities.TRANSPORT_SATELLITE, "satellite");
            }

            internet |= capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            validated |= capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            captivePortal |=
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
            metered |= !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }

        private void addTransport(
                NetworkCapabilities capabilities, int transport, String transportName) {
            if (capabilities.hasTransport(transport)) transports.add(transportName);
        }

        private void addAddress(InetAddress address) {
            if (address == null) return;
            addresses.add(address.getHostAddress());
            if (address instanceof Inet6Address) {
                hasIpv6 = true;
                hasGlobalIpv6 |= !address.isAnyLocalAddress()
                        && !address.isLoopbackAddress()
                        && !address.isLinkLocalAddress()
                        && !address.isMulticastAddress();
            }
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("name", name);
            result.put("display_name", displayName);
            result.put("up", up);
            result.put("active", active);
            result.put("loopback", loopback);
            result.put("virtual", virtual);
            result.put("multicast", multicast);
            result.put("mtu", mtu);
            result.put("has_ipv6", hasIpv6);
            result.put("has_global_ipv6", hasGlobalIpv6);
            result.put("internet", internet);
            result.put("validated", validated);
            result.put("captive_portal", captivePortal);
            result.put("metered", metered);
            result.put("transports", strings(transports, transports.size()));
            result.put("ip_addresses", strings(addresses, MAX_ADDRESSES));
            result.put("dns_servers", strings(dnsServers, MAX_DNS_SERVERS));

            JSONArray routeArray = new JSONArray();
            int count = 0;
            for (RouteSnapshot route : routes.values()) {
                if (count++ >= MAX_ROUTES) break;
                routeArray.put(route.toJson());
            }
            result.put("routes", routeArray);
            result.put("routes_truncated", routes.size() > MAX_ROUTES);
            return result;
        }
    }

    private static final class RouteSnapshot {
        final String destination;
        final String address;
        final int prefix;
        final boolean ipv6;
        final String gateway;
        final boolean hasGateway;
        final boolean defaultRoute;
        final boolean hostRoute;
        final boolean networkRoute;
        final boolean direct;
        final boolean anyLocal;
        final boolean siteLocal;
        final boolean loopback;
        final boolean linkLocal;
        final boolean multicast;

        RouteSnapshot(RouteInfo route) {
            IpPrefix prefixInfo = route.getDestination();
            InetAddress destinationAddress = prefixInfo != null ? prefixInfo.getAddress() : null;
            InetAddress gatewayAddress = route.getGateway();

            destination = prefixInfo != null ? prefixInfo.toString() : "none";
            address = destinationAddress != null ? destinationAddress.getHostAddress() : "none";
            prefix = prefixInfo != null ? prefixInfo.getPrefixLength() : -1;
            ipv6 = destinationAddress instanceof Inet6Address;
            hasGateway = route.hasGateway();
            gateway = hasGateway && gatewayAddress != null ? gatewayAddress.getHostAddress() : "none";
            defaultRoute = route.isDefaultRoute();
            hostRoute = prefix == (ipv6 ? 128 : 32);
            networkRoute = !hostRoute && !defaultRoute;
            direct = !hasGateway;
            anyLocal = destinationAddress != null && destinationAddress.isAnyLocalAddress();
            siteLocal = destinationAddress != null && destinationAddress.isSiteLocalAddress();
            loopback = destinationAddress != null && destinationAddress.isLoopbackAddress();
            linkLocal = destinationAddress != null && destinationAddress.isLinkLocalAddress();
            multicast = destinationAddress != null && destinationAddress.isMulticastAddress();
        }

        String key() {
            return destination + '|' + gateway;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("destination", destination);
            result.put("address", address);
            result.put("prefix", prefix);
            result.put("ipv6", ipv6);
            result.put("gateway", gateway);
            result.put("has_gateway", hasGateway);
            result.put("default", defaultRoute);
            result.put("host_route", hostRoute);
            result.put("network_route", networkRoute);
            result.put("direct", direct);
            result.put("any_local", anyLocal);
            result.put("site_local", siteLocal);
            result.put("loopback", loopback);
            result.put("link_local", linkLocal);
            result.put("multicast", multicast);
            return result;
        }
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
