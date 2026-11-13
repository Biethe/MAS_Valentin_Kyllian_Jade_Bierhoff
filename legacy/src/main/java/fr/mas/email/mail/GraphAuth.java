package fr.mas.email.mail;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.DeviceCodeFlowParameters;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.PublicClientApplication;
import fr.mas.email.config.AppConfig;

import java.net.MalformedURLException;
import java.util.Set;

/**
 * Obtention du jeton OAuth2 pour Microsoft Graph.
 *
 * <p>Deux modes supportes :
 * <ul>
 *   <li>{@code devicecode} (delegue) : l'utilisateur se connecte une fois via
 *       un code affiche dans la console ; ideal en local ;</li>
 *   <li>{@code clientcredentials} (app-only) : necessite une cle secrete et une
 *       boite cible ({@code outlook.mailbox}).</li>
 * </ul>
 */
public final class GraphAuth {

    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";
    private static final Set<String> DELEGATED_SCOPES = Set.of(
            "https://graph.microsoft.com/Mail.ReadWrite",
            "https://graph.microsoft.com/Mail.Send");

    private final AppConfig config;
    private final boolean appOnly;

    private PublicClientApplication publicApp;
    private ConfidentialClientApplication confidentialApp;

    public GraphAuth(AppConfig config) {
        this.config = config;
        this.appOnly = "clientcredentials".equalsIgnoreCase(config.outlookAuthMode());
    }

    public boolean isAppOnly() {
        return appOnly;
    }

    public String accessToken() {
        try {
            IAuthenticationResult result = appOnly ? acquireAppOnly() : acquireDelegated();
            return result.accessToken();
        } catch (Exception e) {
            throw new MailException("Authentification Microsoft Graph impossible", e);
        }
    }

    private IAuthenticationResult acquireDelegated() throws MalformedURLException {
        if (publicApp == null) {
            publicApp = PublicClientApplication.builder(config.outlookClientId())
                    .authority(authority())
                    .build();
        }
        DeviceCodeFlowParameters parameters = DeviceCodeFlowParameters
                .builder(DELEGATED_SCOPES, code -> {
                    System.out.println("----------------------------------------------");
                    System.out.println("Connexion Outlook requise :");
                    System.out.println(code.message());
                    System.out.println("----------------------------------------------");
                })
                .build();
        return publicApp.acquireToken(parameters).join();
    }

    private IAuthenticationResult acquireAppOnly() throws MalformedURLException {
        if (config.outlookClientSecret().isBlank()) {
            throw new MailException("outlook.clientSecret est requis en mode clientcredentials");
        }
        if (confidentialApp == null) {
            confidentialApp = ConfidentialClientApplication
                    .builder(config.outlookClientId(),
                            ClientCredentialFactory.createFromSecret(config.outlookClientSecret()))
                    .authority(authority())
                    .build();
        }
        ClientCredentialParameters parameters = ClientCredentialParameters
                .builder(Set.of(GRAPH_SCOPE))
                .build();
        return confidentialApp.acquireToken(parameters).join();
    }

    private String authority() {
        String tenant = config.outlookTenantId().isBlank() ? "common" : config.outlookTenantId();
        return "https://login.microsoftonline.com/" + tenant;
    }
}
