package ci.esatic.sigep.config;

import ci.esatic.sigep.controller.web.AdminOtpController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Empêche l'accès aux espaces d'administration avant validation du second facteur. */
@Component
public class AdminOtpInterceptor implements HandlerInterceptor {

    /**
     * Interrupteur d'exploitation. Le second facteur dépend d'un courriel : si l'envoi tombe en
     * panne en production, plus personne n'entre dans l'administration — y compris pour réparer
     * l'envoi. Ce drapeau ({@code APP_SECURITY_ADMIN_OTP_ENABLED=false}) rend la main sans
     * redéploiement. Activé par défaut : le désactiver est une décision explicite.
     */
    @org.springframework.beans.factory.annotation.Value("${app.security.admin-otp.enabled:true}")
    private boolean otpActif;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!otpActif) return true;
        if (Boolean.TRUE.equals(request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(AdminOtpController.VERIFIED))) return true;
        response.sendRedirect("/admin-otp");
        return false;
    }
}