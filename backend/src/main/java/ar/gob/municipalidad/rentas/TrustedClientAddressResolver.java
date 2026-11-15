package ar.gob.municipalidad.rentas;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

@Component
class TrustedClientAddressResolver {
    private static final int MAX_FORWARDED_HEADER_LENGTH=1024;
    // shortcut: cap proxy-chain parsing; increase only if the approved edge grows beyond 16 hops.
    private static final int MAX_FORWARDED_HOPS=16;
    private final List<Network> trustedProxies;

    TrustedClientAddressResolver(@Value("${rentas.security.trusted-proxy-cidrs:}") String cidrs) {
        trustedProxies=parseNetworks(cidrs);
    }

    String resolve(HttpServletRequest request) {
        String peer=ip(request.getRemoteAddr());
        if(peer==null||!trusted(peer))return request.getRemoteAddr();
        String forwarded=request.getHeader("X-Forwarded-For");
        if(forwarded==null||forwarded.isBlank()||forwarded.length()>MAX_FORWARDED_HEADER_LENGTH)return peer;
        String[] hops=forwarded.split(",",-1);
        if(hops.length>MAX_FORWARDED_HOPS)return peer;
        String current=peer;
        for(int i=hops.length-1;i>=0&&trusted(current);i--){
            String candidate=ip(hops[i].trim());
            if(candidate==null)return peer;
            current=candidate;
        }
        return current;
    }

    private boolean trusted(String address){
        byte[] bytes=ipBytes(address);
        return bytes!=null&&trustedProxies.stream().anyMatch(network->network.contains(bytes));
    }

    private List<Network> parseNetworks(String cidrs){
        if(cidrs==null||cidrs.isBlank())return List.of();
        List<Network> networks=new ArrayList<>();
        for(String item:cidrs.split(",")){
            String[] parts=item.trim().split("/",-1);
            byte[] address=ipBytes(parts[0]);
            if(address==null||parts.length>2)throw new IllegalArgumentException("CIDR de proxy confiable inválido");
            int bits=address.length*8;
            if(parts.length==2){
                try{bits=Integer.parseInt(parts[1]);}catch(NumberFormatException ex){throw new IllegalArgumentException("CIDR de proxy confiable inválido",ex);}
            }
            if(bits<0||bits>address.length*8)throw new IllegalArgumentException("CIDR de proxy confiable inválido");
            networks.add(new Network(address,bits));
        }
        return List.copyOf(networks);
    }

    private String ip(String value){
        byte[] bytes=ipBytes(value);
        if(bytes==null)return null;
        try{return InetAddress.getByAddress(bytes).getHostAddress();}catch(UnknownHostException ex){return null;}
    }

    private byte[] ipBytes(String value){
        if(value==null||value.isBlank()||!value.matches("[0-9a-fA-F:.]+"))return null;
        try{return InetAddress.getByName(value).getAddress();}catch(UnknownHostException ex){return null;}
    }

    private static final class Network {
        private final byte[] address;
        private final int prefix;

        Network(byte[] address,int prefix){this.address=address;this.prefix=prefix;}

        boolean contains(byte[] candidate){
            if(address.length!=candidate.length)return false;
            int wholeBytes=prefix/8,remainingBits=prefix%8;
            for(int i=0;i<wholeBytes;i++)if(address[i]!=candidate[i])return false;
            if(remainingBits==0)return true;
            int mask=0xff<<(8-remainingBits);
            return (address[wholeBytes]&mask)==(candidate[wholeBytes]&mask);
        }
    }
}
