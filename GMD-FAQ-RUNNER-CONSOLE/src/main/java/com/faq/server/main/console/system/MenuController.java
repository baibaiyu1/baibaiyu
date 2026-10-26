package com.gmd.faq.server.main.console.system;

import com.gmd.faq.server.main.common.dto.MenuSaveDTO;
import com.gmd.faq.server.main.common.entity.Menu;
import com.gmd.faq.server.main.common.enums.MenuType;
import com.gmd.faq.server.main.common.r.R;
import com.gmd.faq.server.main.common.vo.MenuVO;
import com.gmd.faq.server.main.system.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.gmd.faq.server.main.common.config.auth.PermNeed;

/**
 * 菜单管理 Controller
 * <p>
 * 对应「系统设置 - 菜单管理」：菜单树、菜单详情（含按钮权限列表）。
 *
 */
@RestController
@RequestMapping("/menu")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /**
     * 菜单树
     *
     * @param keyword  菜单名称/权限字符/路由地址关键字
     * @param menuType 菜单类型过滤（DIRECTORY/MENU/BUTTON）
     */
    @GetMapping("/tree")
    public R<List<MenuVO>> tree(@RequestParam(value = "keyword", required = false) String keyword,
                                @RequestParam(value = "menuType", required = false) String menuType) {
        return R.data(menuService.listTree(keyword, menuType));
    }

    /**
     * 菜单平铺列表
     */
    @GetMapping("/list")
    public R<List<Menu>> list() {
        return R.data(menuService.listFlat());
    }

    /**
     * 菜单类型可选值（供前端下拉直接渲染）
     */
    @GetMapping("/menu-types")
    public R<List<Map<String, String>>> menuTypes() {
        List<Map<String, String>> options = new ArrayList<>();
        for (MenuType item : MenuType.values()) {
            Map<String, String> option = new LinkedHashMap<>();
            option.put("value", item.name());
            option.put("label", item.getDescription());
            options.add(option);
        }
        return R.data(options);
    }

    /**
     * 菜单详情（含按钮权限列表）
     */
    @GetMapping("/{id}")
    public R<MenuVO> detail(@PathVariable("id") Long id) {
        MenuVO vo = menuService.detail(id);
        if (vo == null) {
            return R.fail("菜单不存在");
        }
        return R.data(vo);
    }

    /**
     * 新增菜单
     */
    @PermNeed({"system:menu:add"})
    @PostMapping
    public R<Menu> save(@RequestBody MenuSaveDTO dto) {
        return R.data(menuService.create(dto), "菜单创建成功");
    }

    /**
     * 修改菜单
     */
    @PermNeed({"system:menu:edit"})
    @PutMapping("/{id}")
    public R<Boolean> update(@PathVariable("id") Long id, @RequestBody MenuSaveDTO dto) {
        dto.setId(id);
        return R.data(menuService.update(dto), "菜单已更新");
    }

    /**
     * 删除菜单
     */
    @PermNeed({"system:menu:remove"})
    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable("id") Long id) {
        return R.data(menuService.remove(id), "菜单已删除");
    }
}